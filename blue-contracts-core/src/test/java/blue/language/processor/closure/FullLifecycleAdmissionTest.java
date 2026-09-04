package blue.language.processor.closure;

import blue.language.identity.CircularSetIdentityCalculator;
import blue.language.identity.CyclicMemberFinalization;
import blue.language.identity.CyclicSetFinalization;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.model.NodeWireForm;
import blue.language.model.wire.BlueLanguageConstants;
import blue.language.provider.NodeProvider;
import blue.language.registry.BlueCoreTypeRegistry;
import blue.language.runtime.BlueLanguage;
import blue.language.processor.BlueContracts;
import blue.language.processor.ContractProcessorRegistry;
import blue.language.processor.ContractProcessorRegistryBuilder;
import blue.language.processor.ChannelProcessor;
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
import blue.language.processor.ProcessorErrorCategory;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.ProcessorContractConstants;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Independent admission proofs for the complete lifecycle queue lane. */
final class FullLifecycleAdmissionTest {

    private static final DocumentId A = new DocumentId("a");
    private static final DocumentId B = new DocumentId("b");
    private static final DocumentId C = new DocumentId("c");

    private static final Node HANDLER_TYPE =
            new Node().name("Full lifecycle admission test Handler");
    private static final String HANDLER_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(HANDLER_TYPE);
    private static final Node EXACT_DOCUMENT_TYPE =
            new Node()
                    .name("Full lifecycle exact-node type")
                    .type(new Node().blueId(
                            BlueLanguageConstants.DICTIONARY_TYPE_BLUE_ID));
    private static final String EXACT_DOCUMENT_TYPE_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(EXACT_DOCUMENT_TYPE);
    private static final Node RETAINED_SOURCE_CHANNEL_TYPE =
            new Node().name("Retained nested source checkpoint Channel");
    private static final String RETAINED_SOURCE_CHANNEL_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(RETAINED_SOURCE_CHANNEL_TYPE);
    private static final Node RETAINED_CATALOG_CHANNEL_TYPE =
            new Node().name("Retained source catalog checkpoint Channel");
    private static final String RETAINED_CATALOG_CHANNEL_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(RETAINED_CATALOG_CHANNEL_TYPE);

    private static final Node EVENT_ONE = event("one");
    private static final Node EVENT_CHILD = event("child");
    private static final Node EVENT_PUBLIC = event("public");
    private static final Node EVENT_PING = event("ping");
    private static final Node EVENT_PONG = event("pong");
    private static final Node EVENT_LOOP = event("loop");

    private static final String EVENT_ONE_BLUE_ID = blueId(EVENT_ONE);
    private static final String EVENT_PING_BLUE_ID = blueId(EVENT_PING);
    private static final String EVENT_LOOP_BLUE_ID = blueId(EVENT_LOOP);

    private static final long GENEROUS_GAS = 100_000L;
    private static final long LOOP_GAS = 20_000L;
    private static final String DUPLICATE_EVENT_IDENTITY_ORACLE =
            "sha256:1cc88d5f61fb903567342efeb2feae1331ab05e3db54e2ffed0e99f5c1c3f561"
                    + "|31JtLEZds6saFSDKKWh4XZrWf63BQywpRUB4wDt766Jo"
                    + "|sha256:9754c1af9e30b527b317e19106acff7238f93150c72aa13a7ec204778c551a95"
                    + "|sha256:f0f923f35df45e850aa1c1605456b415c204313e66296c0337b6ff14765fee53";
    private static final String GAS_FAILURE_ORACLE =
            "sha256:469145475ec0d4b1f8f5320a9c0da8cf791f7688f968ce0b3c96ef755e517ceb"
                    + "|20000|2123"
                    + "|sha256:0d657d72910752d0ae93785673768f7bf8e6d98e1fbb2385ef184885d904a907"
                    + "|GasLimitExceeded"
                    + "|Gas limit exceeded before processor.embeddedEventDelivered"
                    + "|{namespace=processor, counter=embeddedEventDelivered, quantity=1, weight=10, admittedGas=20000, gasLimit=20000, effectiveBudget=20000}"
                    + "|g0:0:PROCESSOR:processInvocation:1:50:50:null:null:null:admission.process"
                    + "|g1:1:PROCESSOR:closureInvocation:1:100:100:null:null:null:admission.closure"
                    + "|g2:2:PROCESSOR:managedDocumentOpened:1:10:10:a:null:null:admission.document.a"
                    + "|g3:3:PROCESSOR:managedDocumentOpened:1:10:10:b:null:null:admission.document.b"
                    + "|g4:4:PROCESSOR:managedOccurrenceBindingVerified:1:5:5:b:null:null:admission.binding.sha256:42c26caa522b69df9e1485ab05a3f7e0085b75e15d48c6a0d70d048ecd6e514a"
                    + "|g5:5:PROCESSOR:processEmbeddedEdgeExamined:1:2:2:b:null:null:admission.edge.sha256:42c26caa522b69df9e1485ab05a3f7e0085b75e15d48c6a0d70d048ecd6e514a"
                    + "|g6:6:PROCESSOR:managedOccurrenceBindingVerified:1:5:5:a:null:null:admission.binding.sha256:6f7a2db7eec312dcb0effa98d6bef40f8735c431eef85a90083a957079e661d5"
                    + "|g7:7:PROCESSOR:processEmbeddedEdgeExamined:1:2:2:a:null:null:admission.edge.sha256:6f7a2db7eec312dcb0effa98d6bef40f8735c431eef85a90083a957079e661d5"
                    + "|rejected:[sha256:a3e16c97b22c3ca4422280c40da11d565a2ae8942e5d84f7bd1c7c80bcd6f223, PROCESSOR, embeddedEventDelivered, 1, 10, 10, SHARED, null, 0, WORK, sha256:6c03056d56b095edba7cfb83fa6b89b327455d64a9a447d9b19157ac62a25186, null, null, null]"
                    + "|works:136:0|INITIALIZATION|a|sha256:18a30d91089c0ba3f678c4e265571f69d694ee54a60fbf410dac16898393635c"
                    + ":135|EMBEDDED_EVENT|b|sha256:6c03056d56b095edba7cfb83fa6b89b327455d64a9a447d9b19157ac62a25186";

    @Test
    void requirement01RootInitializationPatchCommitsExactMarkerState() {
        ProbeProcessor probe = new ProbeProcessor();
        try (DocumentProcessor owner = owner(probe)) {
            Node body = new Node()
                    .name("Initialization patch Root")
                    .properties("state", new Node().value("before"))
                    .contracts(new Node()
                            .properties("lifecycle", lifecycleChannel())
                            .properties("initPatch",
                                    handler("lifecycle")));
            ClosureInvocationInput input = simpleAdmission(
                    owner, A, body, true, GENEROUS_GAS);

            ClosureProcessResult result = full(owner, input, null)
                    .processResult();

            assertSuccess(result);
            ResultingDocument admitted = document(result, A);
            String beforeBlueId = DirectBlueIdCalculator
                    .calculateBlueId(body);
            Node expected = body.clone();
            NodePathEditor.put(
                    expected, "/state", new Node().value("initialized"));
            installInitializedMarker(expected, beforeBlueId);
            String expectedAfter = DirectBlueIdCalculator
                    .calculateBlueId(expected);
            assertEquals(expectedAfter, admitted.afterBlueId());
            assertNodeEquals(expected, admitted.document());
            assertTrue(admitted.initialized());
            assertFalse(admitted.terminated());
            assertEquals(beforeBlueId,
                    admitted.document().getAsNode(
                            "/contracts/initialized/document").getBlueId());
            assertTrue(receipt(result, A).emittedRootEvents().isEmpty());
        }
    }

    @Test
    void requirement02InitializationEventEntersFifoAndReactsLocally() {
        ProbeProcessor probe = new ProbeProcessor();
        try (DocumentProcessor owner = owner(probe)) {
            Node body = new Node()
                    .name("Initialization event Root")
                    .properties("reaction", new Node().value("none"))
                    .contracts(new Node()
                            .properties("lifecycle", lifecycleChannel())
                            .properties("triggered",
                                    triggeredChannel(EVENT_ONE_BLUE_ID))
                            .properties("emitOne", handler("lifecycle"))
                            .properties("reactOne", handler("triggered")));
            ClosureInvocationInput input = simpleAdmission(
                    owner, A, body, true, GENEROUS_GAS);
            Capture capture = new Capture();

            ClosureProcessResult result = full(owner, input, capture)
                    .processResult();

            assertSuccess(result);
            assertEquals("one-reaction",
                    document(result, A).document().getAsText("/reaction"));
            assertEquals(Collections.singletonList(EVENT_ONE_BLUE_ID),
                    eventBlueIds(result.publicEvents()));
            assertEquals(Arrays.asList(
                            WorkKind.INITIALIZATION,
                            WorkKind.LIFECYCLE,
                            WorkKind.TRIGGERED_EVENT),
                    workKinds(capture.evidence));
        }
    }

    @Test
    void requirement03EqualInitializationEventsRetainTwoOccurrences() {
        ProbeProcessor probe = new ProbeProcessor();
        try (DocumentProcessor owner = owner(probe)) {
            Node body = new Node()
                    .name("Duplicate initialization events Root")
                    .properties("count", new Node().value(BigInteger.ZERO))
                    .contracts(new Node()
                            .properties("lifecycle", lifecycleChannel())
                            .properties("triggered",
                                    triggeredChannel(EVENT_ONE_BLUE_ID))
                            .properties("emitTwice", handler("lifecycle"))
                            .properties("countOne", handler("triggered")));
            ClosureInvocationInput input = simpleAdmission(
                    owner, A, body, true, GENEROUS_GAS);

            ClosureProcessResult result = full(owner, input, null)
                    .processResult();

            assertSuccess(result);
            assertEquals(BigInteger.valueOf(2L),
                    document(result, A).document().get("/count"));
            assertEquals(2, result.publicEvents().size());
            PublicEventOccurrence first = result.publicEvents().get(0);
            PublicEventOccurrence second = result.publicEvents().get(1);
            assertEquals(EVENT_ONE_BLUE_ID, first.eventBlueId());
            assertEquals(EVENT_ONE_BLUE_ID, second.eventBlueId());
            assertEquals(0L, first.eventOccurrenceOrdinal());
            assertEquals(1L, second.eventOccurrenceOrdinal());
            assertEquals(DUPLICATE_EVENT_IDENTITY_ORACLE,
                    duplicateEventIdentityProjection(
                            input, first, second));
            assertNotEquals(first.eventOccurrenceIdentity(),
                    second.eventOccurrenceIdentity());
            ManagedDocumentTransitionReceipt receipt = receipt(result, A);
            assertEquals(2, receipt.emittedRootEvents().size());
            assertEquals(EVENT_ONE_BLUE_ID,
                    receipt.emittedRootEvents().get(0).eventBlueId());
            assertEquals(EVENT_ONE_BLUE_ID,
                    receipt.emittedRootEvents().get(1).eventBlueId());
            assertNotEquals(
                    receipt.emittedRootEvents().get(0)
                            .occurrenceIdentity(),
                    receipt.emittedRootEvents().get(1)
                            .occurrenceIdentity());
            assertTrue(result.commitCompanion()
                    .bindsManagedTransitionReceipts());
            assertEquals(result.managedTransitionReceiptsIdentity(),
                    result.commitCompanion()
                            .managedTransitionReceiptsIdentity());
            assertEquals(result.totalGas(),
                    managedTransitionGas(result));
            ClosureCommitCompanion companion = result.commitCompanion();
            assertThrows(IllegalArgumentException.class,
                    () -> new ClosureCommitCompanion(
                            companion.companionIdentity(),
                            companion.invocationIdentity(),
                            companion.inputClosureIdentity(),
                            companion.outputClosureIdentity(),
                            companion.expectedInputGraphGeneration(),
                            companion.expectedInputDocuments(),
                            companion.expectedInputComponents(),
                            companion.inputOccurrenceBindingSetIdentity(),
                            companion.outputGraphGeneration(),
                            companion.resultingDocuments(),
                            companion.resultingComponents(),
                            companion.occurrenceBindingSetIdentity(),
                            companion.graphChangesIdentity(),
                            companion.checkpointWritesIdentity(),
                            companion.subscriptionDeltasIdentity(),
                            companion.publicEventsIdentity(),
                            companion.gasTraceIdentity(),
                            hash('f'),
                            input.environment()));
        }
    }

    @Test
    void requirement04NonPublicEmitterReachesContainingPublicRootOnly() {
        ProbeProcessor probe = new ProbeProcessor();
        try (DocumentProcessor owner = owner(probe)) {
            ClosureEnvironment environment = environment(owner);
            Node child = new Node()
                    .name("Private child")
                    .contracts(new Node()
                            .properties("lifecycle", lifecycleChannel())
                            .properties("childEmit", handler("lifecycle")));
            String childBlueId = blueId(child);
            Node parent = new Node()
                    .name("Public containing Root")
                    .properties("child", new Node().blueId(childBlueId))
                    .properties("observed", new Node().value(Boolean.FALSE))
                    .contracts(new Node()
                            .properties("embedded", processEmbedded("/child"))
                            .properties("fromChild",
                                    embeddedChannel("/child"))
                            .properties("containReact",
                                    handler("fromChild")));
            ManagedOccurrenceBinding binding =
                    ManagedOccurrenceBinding.derived(
                            environment.managedBindingPolicyIdentity(),
                            A,
                            ScopeAddress.embedded("/child", 1L),
                            B,
                            childBlueId,
                            true,
                            null);
            AffectedClosureSnapshot snapshot = finalizedSnapshot(
                    bodies(A, parent, B, child),
                    Collections.singletonList(binding),
                    Collections.singletonList(A));
            ClosureInvocationInput input = admission(
                    snapshot, environment, GENEROUS_GAS);

            ClosureProcessResult result = full(owner, input, null)
                    .processResult();

            assertSuccess(result);
            assertTrue((Boolean) document(result, A).document()
                    .get("/observed"));
            assertTrue(result.publicEvents().isEmpty(),
                    "A private member emission is internal even when a public "
                            + "containing Root reacts to it");
            assertEquals(Collections.singletonList("child"),
                    probe.observedEventKinds);
            ManagedDocumentTransitionReceipt childReceipt = receipt(
                    result, B);
            assertEquals(1, childReceipt.emittedRootEvents().size());
            assertEquals(blueId(EVENT_CHILD), childReceipt
                    .emittedRootEvents().get(0).eventBlueId());
            assertFalse(childReceipt.emittedRootEvents().get(0)
                    .publicAtSource());
        }
    }

    @Test
    void nestedEmitterReachesTransitiveContainingOccurrenceByComposedPath() {
        ProbeProcessor probe = new ProbeProcessor();
        try (DocumentProcessor owner = owner(probe)) {
            ClosureEnvironment environment = environment(owner);
            Node leaf = new Node()
                    .name("Nested private event source")
                    .contracts(new Node()
                            .properties("lifecycle", lifecycleChannel())
                            .properties("childEmit", handler("lifecycle")));
            String leafBlueId = blueId(leaf);
            Node member = new Node()
                    .name("Collection member")
                    .properties("payment", new Node().blueId(leafBlueId))
                    .contracts(new Node().properties(
                            "embedded", processEmbedded("/payment")));
            String memberBlueId = blueId(member);
            Node root = new Node()
                    .name("Collection owner")
                    .properties("orders", new Node().properties(
                            "o1", new Node().blueId(memberBlueId)))
                    .properties("observed", new Node().value(Boolean.FALSE))
                    .contracts(new Node()
                            .properties(
                                    "embedded",
                                    processEmbedded("/orders/o1"))
                            .properties(
                                    "fromNestedPayment",
                                    embeddedChannel(
                                            "/orders/o1/payment"))
                            .properties(
                                    "containReact",
                                    handler("fromNestedPayment")));
            ManagedOccurrenceBinding rootToMember =
                    ManagedOccurrenceBinding.derived(
                            environment.managedBindingPolicyIdentity(),
                            A,
                            ScopeAddress.embedded("/orders/o1", 1L),
                            B,
                            memberBlueId,
                            true,
                            null);
            ManagedOccurrenceBinding memberToLeaf =
                    ManagedOccurrenceBinding.derived(
                            environment.managedBindingPolicyIdentity(),
                            B,
                            ScopeAddress.embedded("/payment", 1L),
                            C,
                            leafBlueId,
                            true,
                            null);
            AffectedClosureSnapshot snapshot = finalizedSnapshot(
                    bodies(A, root, B, member, C, leaf),
                    Arrays.asList(memberToLeaf, rootToMember),
                    Collections.singletonList(A));

            ClosureProcessResult result = full(
                    owner,
                    admission(snapshot, environment, GENEROUS_GAS),
                    null).processResult();

            assertSuccess(result);
            assertEquals(
                    Boolean.TRUE,
                    document(result, A).document().get("/observed"));
            assertEquals(
                    Collections.singletonList("child"),
                    probe.observedEventKinds);
        }
    }

    @Test
    void requirement05PublicRootInitializationEventIsProjectedOnce() {
        ProbeProcessor probe = new ProbeProcessor();
        try (DocumentProcessor owner = owner(probe)) {
            Node body = new Node()
                    .name("Public event Root")
                    .contracts(new Node()
                            .properties("lifecycle", lifecycleChannel())
                            .properties("publicEmit", handler("lifecycle")));
            ClosureInvocationInput input = simpleAdmission(
                    owner, A, body, true, GENEROUS_GAS);

            ClosureProcessResult result = full(owner, input, null)
                    .processResult();

            assertSuccess(result);
            assertEquals(1, result.publicEvents().size());
            PublicEventOccurrence occurrence = result.publicEvents().get(0);
            assertEquals(0L, occurrence.publicEventOrdinal());
            assertEquals(0L, occurrence.eventOccurrenceOrdinal());
            assertEquals(A, occurrence.publicRootDocumentId());
            assertEquals(blueId(EVENT_PUBLIC), occurrence.eventBlueId());
            assertNodeEquals(EVENT_PUBLIC, occurrence.event());
        }
    }

    @Test
    void completeManagedRevisionReceiptRetainsTerminatedSourceWithoutLocalReprocessing() {
        ProbeProcessor probe = new ProbeProcessor();
        Node authoredChild = new Node()
                .name("Retained source")
                .properties("revision", new Node().value(
                        BigInteger.ZERO))
                .contracts(new Node()
                        .properties(
                                "localSourceEvents",
                                triggeredChannel(blueId(EVENT_CHILD)))
                        .properties(
                                "mustNotRun",
                                handler("localSourceEvents")));
        String authoredChildBlueId = blueId(authoredChild);
        final Node childBefore = authoredChild.clone();
        installInitializedMarker(childBefore, authoredChildBlueId);
        final Node childAfter = childBefore.clone();
        NodePathEditor.put(
                childAfter,
                "/revision",
                new Node().value(BigInteger.ONE));
        installTerminatedMarker(
                childAfter, "source-complete", "retained history");
        final String beforeBlueId = blueId(childBefore);
        String afterBlueId = blueId(childAfter);
        final String retainedEventBlueId = blueId(EVENT_CHILD);
        NodeProvider retainedHistory = new NodeProvider() {
            @Override
            public List<Node> fetchByBlueId(String blueId) {
                return beforeBlueId.equals(blueId)
                        ? Collections.singletonList(childBefore.clone())
                        : afterBlueId.equals(blueId)
                        ? Collections.singletonList(childAfter.clone())
                        : retainedEventBlueId.equals(blueId)
                        ? Collections.singletonList(EVENT_CHILD.clone())
                        : Collections.<Node>emptyList();
            }
        };
        try (DocumentProcessor owner = owner(probe, retainedHistory)) {
            ClosureEnvironment environment = environment(owner);

            Node authoredParent = new Node()
                    .name("Retained consumer")
                    .properties("child", new Node().blueId(beforeBlueId))
                    .properties("observed", new Node().value(Boolean.FALSE))
                    .contracts(new Node()
                            .properties("embedded", processEmbedded("/child"))
                            .properties("fromChild", embeddedChannel("/child"))
                            .properties("catchUpReact", handler("fromChild")));
            String authoredParentBlueId = blueId(authoredParent);
            Node parent = authoredParent.clone();
            installInitializedMarker(parent, authoredParentBlueId);

            ManagedOccurrenceBinding historical =
                    ManagedOccurrenceBinding.derived(
                            environment.managedBindingPolicyIdentity(),
                            A,
                            ScopeAddress.embedded("/child", 1L),
                            B,
                            beforeBlueId,
                            false,
                            Long.valueOf(0L));
            AffectedClosureSnapshot base = finalizedSnapshot(
                    bodies(A, parent, B, childAfter),
                    Collections.singletonList(historical),
                    Collections.singletonList(A));
            AffectedClosureSnapshot snapshot = initializedSnapshot(
                    base, 4L, 1L, B);

            String sourceInvocationIdentity = hash('c');
            String sourceEventBlueId = retainedEventBlueId;
            ManagedRootEventOccurrence sourceEvent =
                    new ManagedRootEventOccurrence(
                            0L,
                            0L,
                            B,
                            ClosureIdentityService.INSTANCE
                                    .eventOccurrenceIdentity(
                                            sourceInvocationIdentity,
                                            0L,
                                            sourceEventBlueId),
                            exactEvent(EVENT_CHILD, sourceEventBlueId),
                            true);
            ManagedRootEventOccurrence duplicateSourceEvent =
                    new ManagedRootEventOccurrence(
                            1L,
                            1L,
                            B,
                            ClosureIdentityService.INSTANCE
                                    .eventOccurrenceIdentity(
                                            sourceInvocationIdentity,
                                            1L,
                                            sourceEventBlueId),
                            exactEvent(EVENT_CHILD, sourceEventBlueId),
                            true);
            ManagedDocumentTransitionReceipt sourceReceipt =
                    ManagedDocumentTransitionReceipt.identified(
                            sourceInvocationIdentity,
                            0L,
                            B,
                            hash('d'),
                            beforeBlueId,
                            afterBlueId,
                            Arrays.asList(
                                    sourceEvent, duplicateSourceEvent),
                            9L);
            ManagedRevisionCause cause = ClosureEvidenceFactory
                    .managedRevisionCause(
                            historical.occurrenceIdentity(),
                            0L,
                            1L,
                            snapshot.managedDocument(B).document(),
                            sourceReceipt);
            ExecutionPolicy policy = ClosureEvidenceFactory.executionPolicy(
                    GENEROUS_GAS,
                    Collections.<DocumentId, Long>emptyMap(),
                    "managed-revision-receipt-gas-v1");
            ClosureInvocationInput input = ClosureEvidenceFactory
                    .processClosure(
                            snapshot,
                            cause,
                            Collections.<DirectLogicalDelivery>emptyList(),
                            policy,
                            environment);

            ClosureAttemptResult attempt;
            try (BlueClosureContracts contracts =
                         new BlueClosureContracts(owner)) {
                attempt = contracts.processClosure(input);
            }

            assertTrue(attempt.isComplete(),
                    attempt.kind() + " " + attempt.resourceDemands()
                            + " " + attempt.requiredExactBlueIds()
                            + " before=" + beforeBlueId
                            + " after=" + afterBlueId
                            + " handler=" + HANDLER_BLUE_ID
                            + " childEvent=" + blueId(EVENT_CHILD));
            ClosureProcessResult result = attempt.processResult();
            assertSuccess(result);
            assertEquals(Boolean.TRUE,
                    document(result, A).document().get("/observed"));
            assertEquals(afterBlueId,
                    NodePathEditor.getOrNull(
                            document(result, A).document(), "/child")
                            .getBlueId());
            assertEquals(snapshot.managedDocument(B).blueId(),
                    document(result, B).afterBlueId());
            assertTrue(document(result, B).terminated());
            assertEquals(2, probe.executionCount,
                    "Only the containing handler executes; the terminated "
                            + "source remains unavailable for local delivery");
            assertEquals(Arrays.asList("child", "child"),
                    probe.observedEventKinds);
            assertEquals(Arrays.asList(
                            blueId(EVENT_PUBLIC), blueId(EVENT_PUBLIC)),
                    eventBlueIds(result.publicEvents()));
            ManagedDocumentTransitionReceipt consumerReceipt = receipt(
                    result, A);
            assertEquals(hash('d'),
                    consumerReceipt.originalCauseIdentity());
            assertEquals(2, consumerReceipt.emittedRootEvents().size());
            assertEquals(blueId(EVENT_PUBLIC), consumerReceipt
                    .emittedRootEvents().get(0).eventBlueId());
            assertNotEquals(
                    consumerReceipt.emittedRootEvents().get(0)
                            .occurrenceIdentity(),
                    consumerReceipt.emittedRootEvents().get(1)
                            .occurrenceIdentity());
            assertTrue(consumerReceipt.emittedRootEvents().get(0)
                    .publicAtSource());
            assertEquals(1, result.managedTransitionReceipts().size(),
                    "The authoritative source is not reprocessed");
            ManagedOccurrenceBinding activated = result
                    .occurrenceBindings().get(0);
            assertTrue(activated.active(),
                    "A delivered retained event must not undo activation");
            assertNull(activated.pendingHistoricalEpoch(),
                    "A delivered retained event must not restore its cursor");
            assertEquals(result.totalGas(),
                    managedTransitionGas(result));
        }
    }

    @Test
    void deliveredFinalManagedRevisionEventPreservesActivationAfterParentReaction() {
        ProbeProcessor probe = new ProbeProcessor();
        Node authoredChild = new Node()
                .name("Final retained source")
                .properties("revision", new Node().value(
                        BigInteger.ZERO));
        String authoredChildBlueId = blueId(authoredChild);
        final Node childBefore = authoredChild.clone();
        installInitializedMarker(childBefore, authoredChildBlueId);
        final Node childAfter = childBefore.clone();
        NodePathEditor.put(
                childAfter,
                "/revision",
                new Node().value(BigInteger.ONE));
        installTerminatedMarker(
                childAfter, "source-final", "retained final epoch");
        final String beforeBlueId = blueId(childBefore);
        final String afterBlueId = blueId(childAfter);
        final String sourceEventBlueId = blueId(EVENT_CHILD);
        NodeProvider retainedHistory = new NodeProvider() {
            @Override
            public List<Node> fetchByBlueId(String blueId) {
                return beforeBlueId.equals(blueId)
                        ? Collections.singletonList(childBefore.clone())
                        : afterBlueId.equals(blueId)
                        ? Collections.singletonList(childAfter.clone())
                        : sourceEventBlueId.equals(blueId)
                        ? Collections.singletonList(EVENT_CHILD.clone())
                        : Collections.<Node>emptyList();
            }
        };
        try (DocumentProcessor owner = owner(probe, retainedHistory)) {
            ClosureEnvironment environment = environment(owner);
            Node authoredParent = new Node()
                    .name("Final retained consumer")
                    .properties("child", new Node().blueId(beforeBlueId))
                    .properties("observed", new Node().value(Boolean.FALSE))
                    .contracts(new Node()
                            .properties("embedded", processEmbedded("/child"))
                            .properties("fromChild", embeddedChannel("/child"))
                            .properties("catchUpReact", handler("fromChild")));
            String authoredParentBlueId = blueId(authoredParent);
            Node parent = authoredParent.clone();
            installInitializedMarker(parent, authoredParentBlueId);

            ManagedOccurrenceBinding historical =
                    ManagedOccurrenceBinding.derived(
                            environment.managedBindingPolicyIdentity(),
                            A,
                            ScopeAddress.embedded("/child", 1L),
                            B,
                            beforeBlueId,
                            false,
                            Long.valueOf(0L));
            AffectedClosureSnapshot snapshot = initializedSnapshot(
                    finalizedSnapshot(
                            bodies(A, parent, B, childAfter),
                            Collections.singletonList(historical),
                            Collections.singletonList(A)),
                    4L,
                    1L,
                    B);
            String sourceInvocationIdentity = hash('7');
            ManagedRootEventOccurrence sourceEvent =
                    new ManagedRootEventOccurrence(
                            0L,
                            0L,
                            B,
                            ClosureIdentityService.INSTANCE
                                    .eventOccurrenceIdentity(
                                            sourceInvocationIdentity,
                                            0L,
                                            sourceEventBlueId),
                            exactEvent(EVENT_CHILD, sourceEventBlueId),
                            false);
            ManagedDocumentTransitionReceipt sourceReceipt =
                    ManagedDocumentTransitionReceipt.identified(
                            sourceInvocationIdentity,
                            0L,
                            B,
                            hash('8'),
                            beforeBlueId,
                            afterBlueId,
                            Collections.singletonList(sourceEvent),
                            5L);
            ManagedRevisionCause cause = ClosureEvidenceFactory
                    .managedRevisionCause(
                            historical.occurrenceIdentity(),
                            0L,
                            1L,
                            snapshot.managedDocument(B).document(),
                            sourceReceipt);
            ClosureInvocationInput input = ClosureEvidenceFactory
                    .processClosure(
                            snapshot,
                            cause,
                            Collections.<DirectLogicalDelivery>emptyList(),
                            ClosureEvidenceFactory.executionPolicy(
                                    GENEROUS_GAS,
                                    Collections.<DocumentId, Long>emptyMap(),
                                    "managed-final-event-activation-v1"),
                            environment);

            ClosureAttemptResult attempt;
            try (BlueClosureContracts contracts =
                         new BlueClosureContracts(owner)) {
                attempt = contracts.processClosure(input);
            }

            assertTrue(attempt.isComplete());
            ClosureProcessResult result = attempt.processResult();
            assertSuccess(result);
            assertEquals(Boolean.TRUE,
                    document(result, A).document().get("/observed"));
            ManagedOccurrenceBinding activated = result
                    .occurrenceBindings().get(0);
            assertTrue(activated.active());
            assertNull(activated.pendingHistoricalEpoch());
        }
    }

    @Test
    void retainedNestedExistingLineageUsesReceiptC1WithoutReprocessingSources() {
        ProbeProcessor probe = new ProbeProcessor();
        final Map<String, Node> exactNodes = new LinkedHashMap<String, Node>();
        NodeProvider provider = blueId -> exactNodes.containsKey(blueId)
                ? Collections.singletonList(exactNodes.get(blueId).clone())
                : Collections.<Node>emptyList();
        ContractProcessorRegistry registry = ContractProcessorRegistryBuilder
                .create()
                .register(HANDLER_BLUE_ID, HANDLER_TYPE, probe)
                .register(RETAINED_SOURCE_CHANNEL_BLUE_ID,
                        RETAINED_SOURCE_CHANNEL_TYPE,
                        new RetainedSourceChannelProcessor())
                .build();
        try (DocumentProcessor owner = DocumentProcessor.builder()
                .runtimeRegistry(registry)
                .nodeProvider(new TestNodeProvider(provider))
                .build()) {
            ClosureEnvironment environment = environment(owner);
            Node c0 = retainedNestedSource(owner, exactNodes, "C");
            Node c1 = c0.clone();
            NodePathEditor.put(c1, "/revision", new Node().value(BigInteger.ONE));
            Node c2 = c1.clone();
            NodePathEditor.put(c2, "/revision", new Node().value(BigInteger.valueOf(2L)));
            List<Node> cHistory = Arrays.asList(c0, c1, c2);
            List<ManagedDocumentTransitionReceipt> cReceipts = Arrays.asList(
                    retainedNestedReceipt(C, 1, c0, c1, false),
                    retainedNestedReceipt(C, 2, c1, c2, false));
            Node b0 = retainedNestedSource(owner, exactNodes, "B");
            Node b1 = b0.clone();
            NodePathEditor.put(b1, "/revision", new Node().value(BigInteger.ONE));
            Node b2 = b1.clone();
            NodePathEditor.put(b2, "/revision", new Node().value(BigInteger.valueOf(2L)));
            Node b3 = b2.clone();
            NodePathEditor.put(b3, "/revision",
                    new Node().value(BigInteger.valueOf(3L)));
            NodePathEditor.put(b3, "/child", new Node().blueId(blueId(c1)));
            b3.getContracts().properties("embedded", processEmbedded("/child"));
            List<Node> bHistory = Arrays.asList(b0, b1, b2, b3);
            List<ManagedDocumentTransitionReceipt> bReceipts = Arrays.asList(
                    retainedNestedReceipt(B, 1, b0, b1, false),
                    retainedNestedReceipt(B, 2, b1, b2, false),
                    retainedNestedReceipt(B, 3, b2, b3, true));
            for (Node state : cHistory) {
                exactNodes.put(blueId(state), state.clone());
            }
            for (Node state : bHistory) {
                exactNodes.put(blueId(state), state.clone());
            }
            exactNodes.put(blueId(EVENT_CHILD), EVENT_CHILD.clone());
            exactNodes.put(blueId(EVENT_PUBLIC), EVENT_PUBLIC.clone());
            assertNotEquals(blueId(c1), blueId(c2));
            assertEquals(blueId(c1), b3.getAsNode("/child").getBlueId());
            for (int epoch = 0; epoch < 3; epoch++) {
                assertNull(NodePathEditor.getOrNull(bHistory.get(epoch), "/child"));
            }

            Node authoredA = new Node().name("Retained nested consumer A")
                    .properties("child", new Node().blueId(blueId(b0)))
                    .properties("observed", new Node().value(Boolean.FALSE))
                    .contracts(new Node()
                            .properties("embedded", processEmbedded("/child"))
                            .properties("fromChild", embeddedChannel("/child"))
                            .properties("catchUpReact", handler("fromChild")));
            Node a = authoredA.clone();
            installInitializedMarker(a, blueId(authoredA));
            ManagedOccurrenceBinding aToB = ManagedOccurrenceBinding.derived(
                    environment.managedBindingPolicyIdentity(), A,
                    ScopeAddress.embedded("/child", 1L), B,
                    blueId(b0), false, Long.valueOf(0L));
            ManagedOccurrenceBinding bToC = ManagedOccurrenceBinding.derived(
                    environment.managedBindingPolicyIdentity(), B,
                    ScopeAddress.embedded("/child", 1L), C,
                    blueId(c1), false, Long.valueOf(1L));
            AffectedClosureSnapshot base = finalizedSnapshot(
                    bodies(A, a, B, b3, C, c2),
                    Arrays.asList(aToB, bToC), Arrays.asList(A, B, C));
            ArrayList<ManagedDocumentSnapshot> initialized =
                    new ArrayList<ManagedDocumentSnapshot>();
            for (ManagedDocumentSnapshot value : base.managedDocuments()) {
                initialized.add(new ManagedDocumentSnapshot(value.documentId(),
                        value.blueId(), value.document(), true, false, true,
                        A.equals(value.documentId()) ? 4L
                                : B.equals(value.documentId()) ? 3L : 2L,
                        value.componentGeneration()));
            }
            AffectedClosureSnapshot snapshot = ClosureEvidenceFactory
                    .affectedClosure(base.graphGeneration(), initialized,
                            base.occurrences(), base.components(), Arrays.asList(A, B, C));
            Map<String, Object> retainedBefore = retainedNestedValues(exactNodes);

            for (int epoch = 1; epoch <= 3; epoch++) {
                ManagedDocumentTransitionReceipt sourceReceipt = bReceipts.get(epoch - 1);
                ClosureInvocationInput input = retainedNestedInvocation(
                        snapshot, environment, aToB.occurrenceIdentity(), epoch,
                        bHistory.get(epoch), sourceReceipt);
                assertEquals(sourceReceipt.transitionReceiptIdentity(),
                        ((ManagedRevisionCause) input.cause()).sourceRevisionReceiptIdentity());
                if (epoch == 3) {
                    // At A's contiguous B2 -> B3 boundary, C's current head
                    // cannot replace the exact historical C1 named by B3.
                    Node malformedB3 = b3.clone();
                    NodePathEditor.put(malformedB3, "/child", new Node().blueId(blueId(c2)));
                    ClosureInvocationInput malformed = retainedNestedInvocation(
                            snapshot, environment, aToB.occurrenceIdentity(), 3L,
                            malformedB3, sourceReceipt);
                    IllegalArgumentException mismatch = assertThrows(
                            IllegalArgumentException.class,
                            () -> ClosureInvocationVerifier.verify(
                                    malformed,
                                    owner.administration()::runtimeAccess));
                    assertTrue(mismatch.getMessage().contains("afterBlueId"));
                    assertEquals(retainedBefore, retainedNestedValues(exactNodes));
                }
                Capture capture = new Capture();
                ClosureAttemptResult attempt;
                try (BlueClosureContracts contracts = new BlueClosureContracts(owner, capture)) {
                    attempt = contracts.processClosure(input);
                }
                assertTrue(attempt.isComplete(), "B" + epoch + " boundary: "
                        + attempt.kind() + " " + attempt.resourceDemands());
                ClosureProcessResult result = attempt.processResult();
                assertTrue(result.commits(), "B" + epoch + " boundary: " + diagnostic(result));
                assertEquals(0L, countKind(capture.evidence, WorkKind.INITIALIZATION));
                assertTrue(capture.evidence.workTrace().stream().allMatch(
                        work -> A.equals(work.targetDocumentId())),
                        "Retained B/C are immutable sources, never PROCESS targets");
                assertEquals(1, result.managedTransitionReceipts().size());
                assertEquals(A, result.managedTransitionReceipts().get(0).documentId());
                assertEquals(3L, document(result, B).epoch());
                assertEquals(2L, document(result, C).epoch());
                assertEquals(blueId(b3), document(result, B).afterBlueId());
                assertEquals(blueId(c2), document(result, C).afterBlueId());
                assertNodeEquals(b3, document(result, B).document());
                assertNodeEquals(c2, document(result, C).document());
                ManagedOccurrenceBinding nested = result.occurrenceBindings().stream()
                        .filter(row -> B.equals(row.sourceDocumentId())).findFirst().get();
                assertEquals(bToC.occurrenceIdentity(), nested.occurrenceIdentity());
                assertEquals(bToC.bindingIdentity(), nested.bindingIdentity());
                assertEquals(1L, nested.activationGeneration());
                assertEquals(C, nested.targetDocumentId());
                assertEquals(blueId(c1), nested.expectedTargetBlueId());
                assertEquals(Long.valueOf(1L), nested.pendingHistoricalEpoch());
                assertFalse(nested.active());
                ManagedOccurrenceBinding containing = result.occurrenceBindings().stream()
                        .filter(row -> A.equals(row.sourceDocumentId())).findFirst().get();
                assertEquals(aToB.occurrenceIdentity(), containing.occurrenceIdentity());
                assertEquals(1L, containing.activationGeneration());
                assertEquals(epoch == 3, containing.active());
                assertEquals(epoch == 3 ? null : Long.valueOf(epoch),
                        containing.pendingHistoricalEpoch());
                assertEquals(blueId(bHistory.get(epoch)), containing.expectedTargetBlueId());
                assertEquals(retainedBefore, retainedNestedValues(exactNodes));
                ArrayList<ManagedDocumentSnapshot> after = new ArrayList<ManagedDocumentSnapshot>();
                for (ResultingDocument value : result.resultingDocuments()) {
                    after.add(value.asSnapshot());
                }
                snapshot = ClosureEvidenceFactory.affectedClosure(result.graphGeneration(),
                        after, result.occurrenceBindings(), result.resultingComponents(),
                        Arrays.asList(A, B, C));
            }
            assertEquals(2, probe.executionCount,
                    "Only A's two duplicate retained-event handlers may execute");
            assertTrue(probe.initializationOrder.isEmpty());
            assertEquals(Arrays.asList("child", "child"), probe.observedEventKinds);
            assertEquals(blueId(c1), cReceipts.get(0).afterBlueId());
            assertEquals(blueId(c2), cReceipts.get(1).afterBlueId());
            List<ManagedRootEventOccurrence> duplicates = bReceipts.get(2).emittedRootEvents();
            assertEquals(2, duplicates.size());
            assertEquals(duplicates.get(0).eventBlueId(), duplicates.get(1).eventBlueId());
            assertNotEquals(duplicates.get(0).occurrenceIdentity(), duplicates.get(1).occurrenceIdentity());
            assertEquals(0L, duplicates.get(0).occurrenceOrdinal());
            assertEquals(1L, duplicates.get(1).occurrenceOrdinal());
        }
    }

    @Test
    void externalAttachmentPreservesUntouchedRetainedSourceCheckpointAfterCatalogChange() {
        ProbeProcessor probe = new ProbeProcessor();
        final Map<String, Node> exactNodes = new LinkedHashMap<String, Node>();
        exactNodes.put(HANDLER_BLUE_ID, HANDLER_TYPE.clone());
        exactNodes.put(RETAINED_SOURCE_CHANNEL_BLUE_ID, RETAINED_SOURCE_CHANNEL_TYPE.clone());
        exactNodes.put(RETAINED_CATALOG_CHANNEL_BLUE_ID, RETAINED_CATALOG_CHANNEL_TYPE.clone());
        NodeProvider provider = blueId -> exactNodes.containsKey(blueId)
                ? Collections.singletonList(exactNodes.get(blueId).clone())
                : Collections.<Node>emptyList();
        ContractProcessorRegistry registry = ContractProcessorRegistryBuilder
                .create()
                .register(HANDLER_BLUE_ID, HANDLER_TYPE, probe)
                .register(RETAINED_SOURCE_CHANNEL_BLUE_ID,
                        RETAINED_SOURCE_CHANNEL_TYPE,
                        new RetainedSourceChannelProcessor())
                .register(RETAINED_CATALOG_CHANNEL_BLUE_ID,
                        RETAINED_CATALOG_CHANNEL_TYPE,
                        new RetainedCatalogChannelProcessor())
                .build();
        try (DocumentProcessor owner = DocumentProcessor.builder()
                .runtimeRegistry(registry)
                .nodeProvider(new TestNodeProvider(provider))
                .build()) {
            ClosureEnvironment environment = environment(owner);
            Node c = retainedNestedSource(owner, exactNodes, "C");
            exactNodes.put(blueId(c), c.clone());
            Node bAuthored = recordingDocument("B");
            bAuthored.getContracts().properties("ownerChannel",
                    typed(RETAINED_CATALOG_CHANNEL_BLUE_ID));
            exactNodes.put(blueId(bAuthored), bAuthored.clone());
            Node b = bAuthored.clone();
            installInitializedMarker(b, blueId(bAuthored));
            String frozenDomain;
            String refreshedDomain;
            try (ManagedDocumentStepRuntime runtime = new ManagedDocumentStepRuntime(owner)) {
                GasChargeContext context = GasChargeContext.closure("b", "/",
                        Long.valueOf(0L), Long.valueOf(1L), "ownerChannel",
                        null, null, "fixture.frozen-catalog-checkpoint");
                Node sourceEvent = new Node().name("B activates its nested occurrence")
                        .properties("subscriptionKey", new Node().value("retained-catalog"));
                exactNodes.put(blueId(sourceEvent), sourceEvent.clone());
                ManagedCheckpointCandidate candidate = runtime.classifyExternalDelivery(
                        b,
                        "ownerChannel",
                        ExactEventIdentityEvidence.verify(
                                null,
                                sourceEvent,
                                blueId(sourceEvent),
                                null),
                        context).candidate();
                assertNotNull(candidate);
                frozenDomain = candidate.domain().blueId();
                exactNodes.put(frozenDomain, candidate.domain().exactValue());
                // A completed source operation owns its frozen comparison
                // domain even when it adds a non-Channel catalog entry.
                b.properties("child", new Node().blueId(blueId(c)));
                b.getContracts().properties("embedded", processEmbedded("/child"));
                b = runtime.settleCheckpoints(b,
                        Collections.singletonList(new ManagedCheckpointSettlementEntry(
                                candidate, 0L, context)),
                        (key, ordinal) -> context, context).resultingBody();
                refreshedDomain = runtime.projectRootSubscriptionSurface(b)
                        .externalSubscriptions().get(0).checkpointDomainBlueId();
                assertNotEquals(frozenDomain, refreshedDomain,
                        "The exact active catalog now includes Process Embedded");
                assertEquals(frozenDomain, b.getAsNode(
                        "/contracts/checkpoint/entries/ownerChannel/domain").getBlueId());
            }
            // This pure processor has no Language snapshot manager. Supply the
            // complete old descriptor inline, preserving its exact BlueId.
            String referenceRepresentationBlueId = blueId(b);
            NodePathEditor.put(b, "/contracts/checkpoint/entries/ownerChannel/domain",
                    exactNodes.get(frozenDomain).clone());
            assertEquals(referenceRepresentationBlueId, blueId(b));
            exactNodes.put(blueId(b), b.clone());
            Node a = new Node().name("External retained-source consumer")
                    .properties("observed", new Node().value(Boolean.FALSE))
                    .contracts(new Node()
                            .properties("ownerChannel", typed(RETAINED_SOURCE_CHANNEL_BLUE_ID))
                            .properties("attachRetained", handler("ownerChannel")));
            String aAuthoredBlueId = blueId(a);
            exactNodes.put(aAuthoredBlueId, a.clone());
            installInitializedMarker(a, aAuthoredBlueId);
            ManagedOccurrenceBinding aToB = ManagedOccurrenceBinding.derived(
                    environment.managedBindingPolicyIdentity(), A,
                    ScopeAddress.embedded("/child", 1L), B,
                    blueId(bAuthored), false, Long.valueOf(-1L));
            ManagedOccurrenceBinding bToC = ManagedOccurrenceBinding.derived(
                    environment.managedBindingPolicyIdentity(), B,
                    ScopeAddress.embedded("/child", 1L), C,
                    blueId(c), true, null);
            AffectedClosureSnapshot base = finalizedSnapshot(
                    bodies(A, a, B, b, C, c), Arrays.asList(aToB, bToC),
                    Arrays.asList(A, B, C));
            ArrayList<ManagedDocumentSnapshot> initialized =
                    new ArrayList<ManagedDocumentSnapshot>();
            for (ManagedDocumentSnapshot value : base.managedDocuments()) {
                initialized.add(new ManagedDocumentSnapshot(value.documentId(),
                        value.blueId(), value.document(), true, false, true,
                        A.equals(value.documentId()) ? 4L
                                : B.equals(value.documentId()) ? 3L : 2L,
                        value.componentGeneration()));
            }
            AffectedClosureSnapshot snapshot = ClosureEvidenceFactory.affectedClosure(
                    base.graphGeneration(), initialized, base.occurrences(),
                    base.components(), Arrays.asList(A, B, C));
            Node event = new Node().name("Attach authored retained B")
                    .properties("subscriptionKey", new Node().value("retained-source"))
                    .properties("child", new Node().blueId(blueId(bAuthored)));
            exactNodes.put(blueId(event), event.clone());
            System.out.println("FIXTURE_KNOWN_VALUES frozen=" + frozenDomain
                    + " refreshed=" + refreshedDomain + " event=" + blueId(event));
            ExternalEventCause cause = ClosureEvidenceFactory.externalCause(event, blueId(event),
                    ExternalOrderKey.of(Arrays.<Object>asList(Long.valueOf(1L), "consumer-a")),
                    environment.externalOrderPolicyIdentity());
            ClosureInvocationInput input = ClosureEvidenceFactory.processClosure(snapshot,
                    cause, Collections.singletonList(new DirectLogicalDelivery(
                            ManagedScopeKey.root(A), "ownerChannel", "ownerChannel", 0L)),
                    ClosureEvidenceFactory.executionPolicy(GENEROUS_GAS,
                            Collections.<DocumentId, Long>emptyMap(),
                            "external-retained-source-checkpoint-fixture-v1"), environment);
            Map<String, Object> providerBefore = retainedNestedValues(exactNodes);
            Capture capture = new Capture();
            ClosureAttemptResult attempt;
            try (BlueClosureContracts contracts = new BlueClosureContracts(owner, capture)) {
                attempt = contracts.processClosure(input);
            }
            assertTrue(attempt.isComplete(), () -> "Unexpected resource boundary: "
                    + attempt.kind() + " exact=" + attempt.requiredExactBlueIds()
                    + " demands=" + attempt.resourceDemands().stream()
                            .map(demand -> demand.sourceDocumentId() + demand.sourcePath())
                            .collect(java.util.stream.Collectors.toList()));
            ClosureProcessResult result = attempt.processResult();
            assertSuccess(result);
            assertEquals(Boolean.TRUE, document(result, A).document().get("/observed"));
            assertEquals(5L, document(result, A).epoch());
            assertEquals(1, probe.executionCount, "Only A's attachment handler may execute");
            assertEquals(0L, countKind(capture.evidence, WorkKind.INITIALIZATION));
            assertTrue(capture.evidence.workTrace().stream().allMatch(
                    work -> A.equals(work.targetDocumentId())),
                    "B and C must remain no-work retained source members");
            System.out.println("READ_ONLY_SOURCE_CHECKPOINT frozen=" + frozenDomain
                    + " refreshed=" + refreshedDomain + " before=" + blueId(b)
                    + " after=" + document(result, B).afterBlueId()
                    + " checkpointWrites=" + result.checkpointWrites().stream()
                            .map(write -> write.targetManagedScopeIdentity() + ":"
                                    + write.rawChannelKey() + ":" + write.beforePresent()
                                    + "->" + write.afterPresent())
                            .collect(java.util.stream.Collectors.toList()));
            assertEquals(blueId(b), document(result, B).afterBlueId(),
                    "External A attachment must not clean B's frozen source checkpoint");
            assertNodeEquals(b, document(result, B).document());
            assertEquals(3L, document(result, B).epoch());
            assertEquals(blueId(c), document(result, C).afterBlueId());
            assertNodeEquals(c, document(result, C).document());
            assertEquals(2L, document(result, C).epoch());
            assertEquals(providerBefore, retainedNestedValues(exactNodes));
            assertEquals(1, result.managedTransitionReceipts().size());
            assertEquals(A, result.managedTransitionReceipts().get(0).documentId());
            ManagedOccurrenceBinding retained = result.occurrenceBindings().stream()
                    .filter(row -> B.equals(row.sourceDocumentId())).findFirst().get();
            assertEquals(bToC.bindingIdentity(), retained.bindingIdentity());
            assertEquals(bToC.occurrenceIdentity(), retained.occurrenceIdentity());
            ManagedOccurrenceBinding attached = result.occurrenceBindings().stream()
                    .filter(row -> A.equals(row.sourceDocumentId())).findFirst().get();
            assertEquals(aToB.occurrenceIdentity(), attached.occurrenceIdentity());
            assertEquals(Long.valueOf(-1L), attached.pendingHistoricalEpoch());
            assertFalse(attached.active());
        }
    }

    @Test
    void managedRevisionEventAcceptsAuthenticatedSameEpochComponentRebind() {
        ProbeProcessor probe = new ProbeProcessor();
        final Map<String, Node> exactNodes =
                new LinkedHashMap<String, Node>();
        NodeProvider retainedHistory = new NodeProvider() {
            @Override
            public List<Node> fetchByBlueId(String blueId) {
                Node found = exactNodes.get(blueId);
                return found == null
                        ? Collections.<Node>emptyList()
                        : Collections.singletonList(found.clone());
            }
        };
        try (DocumentProcessor owner = owner(probe, retainedHistory)) {
            ManagedComponentRebindScenario scenario =
                    managedComponentRebindScenario(
                            owner, exactNodes, true);

            ClosureAttemptResult attempt;
            try (BlueClosureContracts contracts =
                         new BlueClosureContracts(owner)) {
                attempt = contracts.processClosure(scenario.input);
            }

            assertTrue(attempt.isComplete());
            ClosureProcessResult result = attempt.processResult();
            assertSuccess(result);
            assertNotEquals(
                    scenario.receiptAfterBlueId,
                    document(result, B).afterBlueId(),
                    "the fixture must exercise a same-epoch representation");
            assertEquals(
                    scenario.input.snapshot().managedDocument(B).epoch(),
                    document(result, B).epoch(),
                    "an imported receipt event may re-encode the retained "
                            + "source's cyclic reference without creating "
                            + "another source epoch");
            assertEquals(
                    scenario.input.snapshot().managedDocument(A).epoch() + 1L,
                    document(result, A).epoch(),
                    "the directly processed containing document remains "
                            + "strictly epoch-advancing");
            assertEquals(Boolean.TRUE,
                    document(result, A).document().get("/observed"));
            assertTrue(result.resultingComponents().stream()
                    .anyMatch(component -> component.kind()
                            == ComponentKind.CYCLIC
                            && component.orderedMemberDocumentIds()
                                    .equals(Arrays.asList(A, B))));
            ManagedOccurrenceBinding activated = result
                    .occurrenceBindings().stream()
                    .filter(binding -> binding.sourceDocumentId().equals(A))
                    .findFirst().orElseThrow(AssertionError::new);
            assertTrue(activated.active());
            assertNull(activated.pendingHistoricalEpoch());
        }
    }

    @Test
    void managedRevisionActivationPreservesFinalizedCyclicSideReferences() {
        ProbeProcessor probe = new ProbeProcessor();
        final Map<String, Node> exactNodes =
                new LinkedHashMap<String, Node>();
        NodeProvider retainedHistory = new NodeProvider() {
            @Override
            public List<Node> fetchByBlueId(String blueId) {
                Node found = exactNodes.get(blueId);
                return found == null
                        ? Collections.<Node>emptyList()
                        : Collections.singletonList(found.clone());
            }
        };
        try (DocumentProcessor owner = owner(probe, retainedHistory)) {
            ClosureInvocationInput input =
                    managedCyclicSideActivationScenario(owner, exactNodes);

            ClosureAttemptResult attempt;
            try (BlueClosureContracts contracts =
                         new BlueClosureContracts(owner)) {
                attempt = contracts.processClosure(input);
            }

            assertEquals(ClosureAttemptResult.Kind.COMPLETE, attempt.kind());
            assertTrue(attempt.resourceDemands().isEmpty());
            assertTrue(attempt.requiredExactBlueIds().isEmpty());
            ClosureProcessResult result = attempt.processResult();
            assertSuccess(result);

            ResultingDocument parent = document(result, A);
            ResultingDocument peer = document(result, B);
            ResultingDocument source = document(result, C);
            assertNotEquals(
                    input.snapshot().managedDocument(A).blueId(),
                    parent.afterBlueId(),
                    "the source replacement must re-finalize the parent "
                            + "cycle member before activation");
            assertNotEquals(
                    input.snapshot().managedDocument(B).blueId(),
                    peer.afterBlueId(),
                    "the source replacement must re-finalize the peer "
                            + "cycle member before activation");
            assertEquals(peer.afterBlueId(), NodePathEditor.getOrNull(
                    parent.document(), "/peer").getBlueId());
            assertEquals(parent.afterBlueId(), NodePathEditor.getOrNull(
                    peer.document(), "/parent").getBlueId());
            assertEquals(source.afterBlueId(), NodePathEditor.getOrNull(
                    parent.document(), "/source").getBlueId());
            assertTrue(result.occurrenceBindings().stream()
                    .anyMatch(binding -> binding.active()
                            && binding.sourceDocumentId().equals(A)
                            && binding.sourcePath().equals("/peer")
                            && binding.targetDocumentId().equals(B)));
            assertTrue(result.occurrenceBindings().stream()
                    .anyMatch(binding -> binding.active()
                            && binding.sourceDocumentId().equals(B)
                            && binding.sourcePath().equals("/parent")
                            && binding.targetDocumentId().equals(A)));
            assertTrue(result.occurrenceBindings().stream()
                    .anyMatch(binding -> binding.active()
                            && binding.sourceDocumentId().equals(A)
                            && binding.sourcePath().equals("/source")
                            && binding.targetDocumentId().equals(C)
                            && binding.pendingHistoricalEpoch() == null));
            assertTrue(result.resultingComponents().stream()
                    .anyMatch(component -> component.kind()
                            == ComponentKind.CYCLIC
                            && component.orderedMemberDocumentIds()
                                    .equals(Arrays.asList(A, B))));
            ManagedDocumentSnapshot retainedSource =
                    input.snapshot().managedDocument(C);
            assertEquals(retainedSource.blueId(), source.afterBlueId());
            assertEquals(retainedSource.epoch(), source.epoch());
            assertNodeEquals(retainedSource.document(), source.document());
        }
    }

    @Test
    void managedRevisionEventRejectsUnprovenSameEpochComponentRebind() {
        ProbeProcessor probe = new ProbeProcessor();
        final Map<String, Node> exactNodes =
                new LinkedHashMap<String, Node>();
        NodeProvider retainedHistory = new NodeProvider() {
            @Override
            public List<Node> fetchByBlueId(String blueId) {
                Node found = exactNodes.get(blueId);
                return found == null
                        ? Collections.<Node>emptyList()
                        : Collections.singletonList(found.clone());
            }
        };
        try (DocumentProcessor owner = owner(probe, retainedHistory)) {
            ManagedComponentRebindScenario scenario =
                    managedComponentRebindScenario(
                            owner, exactNodes, false);

            ClosureAttemptResult attempt;
            try (BlueClosureContracts contracts =
                         new BlueClosureContracts(owner)) {
                attempt = contracts.processClosure(scenario.input);
            }

            assertTrue(attempt.isComplete());
            ClosureProcessResult result = attempt.processResult();
            assertEquals(ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                    result.status());
            assertEquals(ProcessorErrorCategory.ManagedOccurrenceBindingMissing,
                    result.diagnostic().category());
            assertEquals(
                    "Imported managed event target no longer proves the source transition",
                    result.diagnostic().message());
            assertEquals(0, probe.executionCount,
                    "unproven source content must fail before event delivery");
        }
    }

    @Test
    void managedRevisionReceiptEventMayRetireItsActivatedOccurrence() {
        assertManagedRevisionReceiptEventRetirement(false);
        assertManagedRevisionReceiptEventRetirement(true);
    }

    @Test
    void managedRevisionFinalReceiptEventMayRetargetToExactHistoricalLineage() {
        assertManagedRevisionReceiptEventRetirement(false, true);
    }

    @Test
    void managedRevisionIntermediateReceiptEventMayRetargetToExactHistoricalLineage() {
        assertManagedRevisionReceiptEventRetirement(true, true);
    }

    private static void assertManagedRevisionReceiptEventRetirement(
            boolean intermediateEpoch) {
        assertManagedRevisionReceiptEventRetirement(intermediateEpoch, false);
    }

    private static void assertManagedRevisionReceiptEventRetirement(
            boolean intermediateEpoch,
            boolean retarget) {
        ProbeProcessor probe = new ProbeProcessor();
        Node authoredChild = new Node()
                .name("Self-retiring retained source")
                .properties("revision", new Node().value(BigInteger.ZERO));
        String authoredChildBlueId = blueId(authoredChild);
        final Node childBefore = authoredChild.clone();
        installInitializedMarker(childBefore, authoredChildBlueId);
        final Node childAfter = childBefore.clone();
        NodePathEditor.put(
                childAfter,
                "/revision",
                new Node().value(BigInteger.ONE));
        final Node childHead = childAfter.clone();
        if (intermediateEpoch) {
            NodePathEditor.put(
                    childHead,
                    "/revision",
                    new Node().value(BigInteger.valueOf(2L)));
        }
        final String beforeBlueId = blueId(childBefore);
        final String afterBlueId = blueId(childAfter);
        final String headBlueId = blueId(childHead);
        final String eventBlueId = blueId(EVENT_CHILD);
        Node authoredReplacement = new Node()
                .name("Existing historical retarget source")
                .properties("revision", new Node().value(BigInteger.ZERO));
        final Node replacementOne = authoredReplacement.clone();
        installInitializedMarker(replacementOne, blueId(authoredReplacement));
        NodePathEditor.put(replacementOne, "/revision", new Node().value(BigInteger.ONE));
        final Node replacementHead = replacementOne.clone();
        NodePathEditor.put(replacementHead, "/revision", new Node().value(BigInteger.valueOf(3L)));
        final String replacementOneId = blueId(replacementOne);
        final String replacementHeadId = blueId(replacementHead);
        NodeProvider retainedHistory = new NodeProvider() {
            @Override
            public List<Node> fetchByBlueId(String blueId) {
                return beforeBlueId.equals(blueId)
                        ? Collections.singletonList(childBefore.clone())
                        : afterBlueId.equals(blueId)
                        ? Collections.singletonList(childAfter.clone())
                        : headBlueId.equals(blueId)
                        ? Collections.singletonList(childHead.clone())
                        : eventBlueId.equals(blueId)
                        ? Collections.singletonList(EVENT_CHILD.clone())
                        : replacementOneId.equals(blueId)
                        ? Collections.singletonList(replacementOne.clone())
                        : replacementHeadId.equals(blueId)
                        ? Collections.singletonList(replacementHead.clone())
                        : Collections.<Node>emptyList();
            }
        };
        try (DocumentProcessor owner = owner(probe, retainedHistory)) {
            ClosureEnvironment environment = environment(owner);
            Node authoredParent = new Node()
                    .name("Self-retiring retained consumer")
                    .properties("child", new Node().blueId(beforeBlueId))
                    .properties(
                            "deliveries", new Node().value(BigInteger.ZERO))
                    .contracts(new Node()
                            .properties("embedded", processEmbedded("/child"))
                            .properties("fromChild", embeddedChannel("/child"))
                            .properties(
                                    retarget ? "catchUpRetarget" : "catchUpDetach",
                                    handler("fromChild")));
            if (retarget) {
                authoredParent.properties("candidate", replacementOne.clone());
            }
            String authoredParentBlueId = blueId(authoredParent);
            Node parent = authoredParent.clone();
            installInitializedMarker(parent, authoredParentBlueId);
            ManagedOccurrenceBinding historical =
                    ManagedOccurrenceBinding.derived(
                            environment.managedBindingPolicyIdentity(),
                            A,
                            ScopeAddress.embedded("/child", 1L),
                            B,
                            beforeBlueId,
                            false,
                            Long.valueOf(0L));
            AffectedClosureSnapshot snapshot = initializedSnapshot(
                    finalizedSnapshot(
                            retarget ? bodies(A, parent, B, childHead, C, replacementHead)
                                    : bodies(A, parent, B, childHead),
                            Collections.singletonList(historical),
                            Collections.singletonList(A)),
                    4L,
                    intermediateEpoch ? 2L : 1L);
            if (retarget) {
                List<ManagedDocumentSnapshot> snapshots = new ArrayList<>();
                for (ManagedDocumentSnapshot member : snapshot.managedDocuments()) {
                    snapshots.add(new ManagedDocumentSnapshot(member.documentId(),
                            member.blueId(), member.document(), member.initialized(),
                            member.terminated(), member.publicRoot(),
                            C.equals(member.documentId()) ? 3L : member.epoch(),
                            member.componentGeneration()));
                }
                snapshot = ClosureEvidenceFactory.affectedClosure(
                        snapshot.graphGeneration(), snapshots, snapshot.occurrences(),
                        snapshot.components(), snapshot.publicRootDocumentIds());
            }

            String sourceInvocationIdentity = hash('6');
            ManagedRootEventOccurrence sourceEvent =
                    new ManagedRootEventOccurrence(
                            0L,
                            0L,
                            B,
                            ClosureIdentityService.INSTANCE
                                    .eventOccurrenceIdentity(
                                            sourceInvocationIdentity,
                                            0L,
                                            eventBlueId),
                            exactEvent(EVENT_CHILD, eventBlueId),
                            false);
            ManagedRootEventOccurrence duplicateSourceEvent =
                    new ManagedRootEventOccurrence(
                            1L,
                            1L,
                            B,
                            ClosureIdentityService.INSTANCE
                                    .eventOccurrenceIdentity(
                                            sourceInvocationIdentity,
                                            1L,
                                            eventBlueId),
                            exactEvent(EVENT_CHILD, eventBlueId),
                            false);
            ManagedDocumentTransitionReceipt sourceReceipt =
                    ManagedDocumentTransitionReceipt.identified(
                            sourceInvocationIdentity,
                            0L,
                            B,
                            hash('9'),
                            beforeBlueId,
                            afterBlueId,
                            retarget ? Collections.singletonList(sourceEvent)
                                    : Arrays.asList(sourceEvent, duplicateSourceEvent),
                            5L);
            ManagedRevisionCause cause = ClosureEvidenceFactory
                    .managedRevisionCause(
                            historical.occurrenceIdentity(),
                            0L,
                            1L,
                            childAfter,
                            sourceReceipt);
            ClosureInvocationInput input = ClosureEvidenceFactory
                    .processClosure(
                            snapshot,
                            cause,
                            Collections.<DirectLogicalDelivery>emptyList(),
                            ClosureEvidenceFactory.executionPolicy(
                                    GENEROUS_GAS,
                                    Collections.<DocumentId, Long>emptyMap(),
                                    intermediateEpoch
                                            ? "managed-intermediate-receipt-event-retirement-v1"
                                            : "managed-final-receipt-event-retirement-v1"),
                            environment);

            ClosureAttemptResult attempt;
            List<ClosureImplementationEvidence> observed = new ArrayList<>();
            try (BlueClosureContracts contracts =
                         new BlueClosureContracts(owner, observed::add)) {
                attempt = contracts.processClosure(input);
                if (retarget) {
                    assertFalse(attempt.isComplete(), attempt.isComplete()
                            ? attempt.processResult().diagnostic().message()
                            : "exact historical C must first require its own evidence");
                    assertEquals(1, attempt.resourceDemands().size());
                    ManagedOccurrenceEvidenceDemand demand =
                            (ManagedOccurrenceEvidenceDemand) attempt.resourceDemands().get(0);
                    assertEquals(A, demand.sourceDocumentId());
                    assertEquals("/child", demand.sourcePath());
                    assertEquals(replacementOneId, demand.suppliedValueBlueId());
                    assertEquals(beforeBlueId, NodePathEditor.getOrNull(
                            input.snapshot().managedDocument(A).document(), "/child")
                            .getBlueId());
                    assertEquals(BigInteger.ZERO,
                            input.snapshot().managedDocument(A).document().get("/deliveries"));
                    ManagedOccurrenceEvidenceResolution invalid =
                            ManagedOccurrenceEvidenceResolution.derived(demand, B, 1L);
                    int executionsBeforeInvalidEvidence = probe.executionCount;
                    assertThrows(IllegalArgumentException.class,
                            () -> contracts.processClosureRetry(ClosureProcessRetryInput.derived(
                                    input, Collections.singletonList(invalid))));
                    ManagedOccurrenceEvidenceDemand wrongPath =
                            ManagedOccurrenceEvidenceDemand.derived(
                                    demand.logicalCauseIdentity(), demand.inputClosureIdentity(),
                                    demand.inputGraphGeneration(), A, "/wrong-child",
                                    demand.processEmbeddedDeclarationIdentity(),
                                    demand.suppliedValueBlueId(), demand.demandOrdinal());
                    ManagedOccurrenceEvidenceDemand wrongCause =
                            ManagedOccurrenceEvidenceDemand.derived(
                                    hash('e'), demand.inputClosureIdentity(),
                                    demand.inputGraphGeneration(), A, "/child",
                                    demand.processEmbeddedDeclarationIdentity(),
                                    demand.suppliedValueBlueId(), demand.demandOrdinal());
                    ManagedOccurrenceEvidenceDemand wrongGeneration =
                            ManagedOccurrenceEvidenceDemand.derived(
                                    demand.logicalCauseIdentity(), demand.inputClosureIdentity(),
                                    demand.inputGraphGeneration() + 1L, A, "/child",
                                    demand.processEmbeddedDeclarationIdentity(),
                                    demand.suppliedValueBlueId(), demand.demandOrdinal());
                    for (ManagedOccurrenceEvidenceDemand invalidDemand
                            : Arrays.asList(wrongPath, wrongCause, wrongGeneration)) {
                        ManagedOccurrenceEvidenceResolution invalidResolution =
                                ManagedOccurrenceEvidenceResolution.derived(invalidDemand, C, 1L);
                        assertThrows(IllegalArgumentException.class,
                                () -> contracts.processClosureRetry(ClosureProcessRetryInput.derived(
                                        input, Collections.singletonList(invalidResolution))));
                    }
                    ManagedRevisionCause unverifiedEvents = ClosureEvidenceFactory.managedRevisionCause(
                            historical.occurrenceIdentity(), B, 0L, 1L,
                            beforeBlueId, afterBlueId, childAfter, sourceReceipt.originalCauseIdentity());
                    ClosureInvocationInput unverifiedInput = ClosureEvidenceFactory.processClosure(
                            input.snapshot(), unverifiedEvents,
                            Collections.<DirectLogicalDelivery>emptyList(), input.executionPolicy(), environment);
                    ManagedOccurrenceEvidenceDemand unverifiedDemand = ManagedOccurrenceEvidenceDemand.derived(
                            unverifiedEvents.causeIdentity(), unverifiedInput.snapshot().closureIdentity(),
                            unverifiedInput.snapshot().graphGeneration(), A, "/child",
                            demand.processEmbeddedDeclarationIdentity(), replacementOneId, 0L);
                    assertThrows(IllegalArgumentException.class,
                            () -> contracts.processClosureRetry(ClosureProcessRetryInput.derived(
                                    unverifiedInput, Collections.singletonList(
                                            ManagedOccurrenceEvidenceResolution.derived(unverifiedDemand, C, 1L)))));
                    assertEquals(executionsBeforeInvalidEvidence, probe.executionCount,
                            "unverified or foreign retarget evidence must fail before Root work");
                    ManagedOccurrenceEvidenceResolution exact =
                            ManagedOccurrenceEvidenceResolution.derived(demand, C, 1L);
                    ManagedOccurrenceEvidenceDemand unusedDemand = ManagedOccurrenceEvidenceDemand.derived(
                            demand.logicalCauseIdentity(), demand.inputClosureIdentity(),
                            demand.inputGraphGeneration(), A, "/child",
                            demand.processEmbeddedDeclarationIdentity(), replacementHeadId, 0L);
                    IllegalArgumentException unused = assertThrows(IllegalArgumentException.class,
                            () -> contracts.processClosureRetry(ClosureProcessRetryInput.derived(
                                    input, Arrays.asList(exact,
                                            ManagedOccurrenceEvidenceResolution.derived(unusedDemand, C, 3L)))));
                    assertTrue(unused.getMessage().contains("unused"));
                    assertEquals(BigInteger.ZERO,
                            input.snapshot().managedDocument(A).document().get("/deliveries"));
                    attempt = contracts.processClosureRetry(ClosureProcessRetryInput.derived(
                            input, Collections.singletonList(exact)));
                }
            }

            assertTrue(attempt.isComplete());
            ClosureProcessResult result = attempt.processResult();
            assertSuccess(result);
            if (retarget) {
                ManagedOccurrenceBinding replacement = result.occurrenceBindings().stream()
                        .filter(row -> row.sourceDocumentId().equals(A)
                                && row.sourcePath().equals("/child"))
                        .findFirst().orElseThrow(AssertionError::new);
                assertEquals(C, replacement.targetDocumentId());
                assertEquals(2L, replacement.activationGeneration());
                assertFalse(replacement.active());
                assertEquals(Long.valueOf(1L), replacement.pendingHistoricalEpoch());
                assertEquals(replacementOneId, replacement.expectedTargetBlueId());
                assertNotEquals(historical.occurrenceIdentity(), replacement.occurrenceIdentity());
                assertEquals(BigInteger.ONE, document(result, A).document().get("/deliveries"));
                assertEquals(snapshot.managedDocument(B).blueId(), document(result, B).afterBlueId());
                assertEquals(snapshot.managedDocument(C).blueId(), document(result, C).afterBlueId());
                assertEquals(3, probe.executionCount,
                        "suspended, unused-evidence, and committed event attempts; no source execution");
                assertTrue(probe.initializationOrder.isEmpty());
                assertFalse(observed.isEmpty());
                assertTrue(observed.get(observed.size() - 1).documentStepTrace().stream()
                        .allMatch(step -> step.targetDocumentId().equals(A)),
                        "only the consumer Root may execute; retained B/C are immutable inputs");
                System.out.println("MANAGED_RECEIPT_RETARGET intermediate=" + intermediateEpoch
                        + " C1=" + replacementOneId + " C3=" + replacementHeadId
                        + " generation=" + replacement.activationGeneration()
                        + " pendingEpoch=" + replacement.pendingHistoricalEpoch());
                return;
            }
            assertNull(NodePathEditor.getOrNull(
                    document(result, A).document(), "/child"));
            assertEquals(BigInteger.valueOf(2L),
                    document(result, A).document().get("/deliveries"));
            assertFalse(result.occurrenceBindings().stream()
                    .anyMatch(binding -> binding.occurrenceIdentity().equals(
                            historical.occurrenceIdentity())));
            ManagedOccurrenceBinding retired = result
                    .occurrenceBindings().stream()
                    .filter(binding -> binding.sourceDocumentId().equals(A)
                            && binding.sourcePath().equals("/child"))
                    .findFirst().orElseThrow(AssertionError::new);
            assertFalse(retired.active());
            assertNull(retired.pendingHistoricalEpoch());
            assertEquals(2L, retired.activationGeneration());
            assertEquals(snapshot.managedDocument(B).blueId(),
                    retired.expectedTargetBlueId());
            assertFalse(result.occurrenceBindings().stream()
                    .anyMatch(binding -> binding.active()
                            && binding.sourceDocumentId().equals(A)
                            && binding.targetDocumentId().equals(B)));
            assertEquals(2, probe.executionCount);
        }
    }

    @Test
    void managedRevisionStillRejectsAnotherTerminatedMember() {
        ProbeProcessor probe = new ProbeProcessor();
        Node authoredChild = new Node()
                .name("Live retained source")
                .properties("revision", new Node().value(BigInteger.ZERO));
        String authoredChildBlueId = blueId(authoredChild);
        final Node childBefore = authoredChild.clone();
        installInitializedMarker(childBefore, authoredChildBlueId);
        final Node childAfter = childBefore.clone();
        NodePathEditor.put(
                childAfter,
                "/revision",
                new Node().value(BigInteger.ONE));
        final String beforeBlueId = blueId(childBefore);
        final String afterBlueId = blueId(childAfter);
        NodeProvider retainedHistory = new NodeProvider() {
            @Override
            public List<Node> fetchByBlueId(String blueId) {
                return beforeBlueId.equals(blueId)
                        ? Collections.singletonList(childBefore.clone())
                        : afterBlueId.equals(blueId)
                        ? Collections.singletonList(childAfter.clone())
                        : Collections.<Node>emptyList();
            }
        };
        try (DocumentProcessor owner = owner(probe, retainedHistory)) {
            ClosureEnvironment environment = environment(owner);
            Node authoredParent = new Node()
                    .name("Unrelated terminated containing member")
                    .properties("child", new Node().blueId(beforeBlueId))
                    .contracts(new Node()
                            .properties("embedded", processEmbedded("/child")));
            String authoredParentBlueId = blueId(authoredParent);
            Node parent = authoredParent.clone();
            installInitializedMarker(parent, authoredParentBlueId);
            installTerminatedMarker(
                    parent, "unrelated-complete", "must remain rejected");
            ManagedOccurrenceBinding historical =
                    ManagedOccurrenceBinding.derived(
                            environment.managedBindingPolicyIdentity(),
                            A,
                            ScopeAddress.embedded("/child", 1L),
                            B,
                            beforeBlueId,
                            false,
                            Long.valueOf(0L));
            AffectedClosureSnapshot base = finalizedSnapshot(
                    bodies(A, parent, B, childAfter),
                    Collections.singletonList(historical),
                    Collections.singletonList(A));
            AffectedClosureSnapshot snapshot = initializedSnapshot(
                    base, 4L, 1L, A);
            ManagedDocumentTransitionReceipt sourceReceipt =
                    ManagedDocumentTransitionReceipt.identified(
                            hash('a'),
                            0L,
                            B,
                            hash('b'),
                            beforeBlueId,
                            afterBlueId,
                            Collections.<ManagedRootEventOccurrence>emptyList(),
                            3L);
            ManagedRevisionCause cause = ClosureEvidenceFactory
                    .managedRevisionCause(
                            historical.occurrenceIdentity(),
                            0L,
                            1L,
                            snapshot.managedDocument(B).document(),
                            sourceReceipt);
            ClosureInvocationInput input = ClosureEvidenceFactory
                    .processClosure(
                            snapshot,
                            cause,
                            Collections.<DirectLogicalDelivery>emptyList(),
                            ClosureEvidenceFactory.executionPolicy(
                                    GENEROUS_GAS,
                                    Collections.<DocumentId, Long>emptyMap(),
                                    "managed-revision-terminated-member-v1"),
                            environment);

            ClosureAttemptResult attempt;
            try (BlueClosureContracts contracts =
                         new BlueClosureContracts(owner)) {
                attempt = contracts.processClosure(input);
            }

            assertTrue(attempt.isComplete());
            ClosureProcessResult result = attempt.processResult();
            assertEquals(ProcessorStatus.CAPABILITY_FAILURE,
                    result.status());
            assertNotNull(result.diagnostic());
            assertEquals(
                    "TERMINATED_MEMBER_POLICY_REQUIRED",
                    result.diagnostic().detail("closureCapability"));
            assertTrue(result.managedTransitionReceipts().isEmpty());
            assertEquals(0, probe.executionCount);
        }
    }

    @Test
    void managedRevisionEventOnlyTransitionProducesBoundReceipt() {
        ProbeProcessor probe = new ProbeProcessor();
        Node authoredChild = new Node().name("Event-only retained source");
        String authoredChildBlueId = blueId(authoredChild);
        final Node child = authoredChild.clone();
        installInitializedMarker(child, authoredChildBlueId);
        final String childBlueId = blueId(child);
        NodeProvider retained = new NodeProvider() {
            @Override
            public List<Node> fetchByBlueId(String blueId) {
                return childBlueId.equals(blueId)
                        ? Collections.singletonList(child.clone())
                        : Collections.<Node>emptyList();
            }
        };
        try (DocumentProcessor owner = owner(probe, retained)) {
            ClosureEnvironment environment = environment(owner);
            Node authoredParent = new Node()
                    .name("Event-only retained consumer")
                    .properties("child", new Node().blueId(childBlueId))
                    .contracts(new Node()
                            .properties("embedded", processEmbedded("/child"))
                            .properties("fromChild", embeddedChannel("/child"))
                            .properties(
                                    "catchUpEmitOnly",
                                    handler("fromChild")));
            String authoredParentBlueId = blueId(authoredParent);
            Node parent = authoredParent.clone();
            installInitializedMarker(parent, authoredParentBlueId);
            ManagedOccurrenceBinding historical =
                    ManagedOccurrenceBinding.derived(
                            environment.managedBindingPolicyIdentity(),
                            A,
                            ScopeAddress.embedded("/child", 1L),
                            B,
                            childBlueId,
                            false,
                            Long.valueOf(0L));
            AffectedClosureSnapshot snapshot = initializedSnapshot(
                    finalizedSnapshot(
                            bodies(A, parent, B, child),
                            Collections.singletonList(historical),
                            Collections.singletonList(A)),
                    4L,
                    1L);
            String sourceInvocationIdentity = hash('e');
            String sourceEventBlueId = blueId(EVENT_CHILD);
            ManagedRootEventOccurrence sourceEvent =
                    new ManagedRootEventOccurrence(
                            0L,
                            0L,
                            B,
                            ClosureIdentityService.INSTANCE
                                    .eventOccurrenceIdentity(
                                            sourceInvocationIdentity,
                                            0L,
                                            sourceEventBlueId),
                            exactEvent(EVENT_CHILD, sourceEventBlueId),
                            false);
            ManagedDocumentTransitionReceipt sourceReceipt =
                    ManagedDocumentTransitionReceipt.identified(
                            sourceInvocationIdentity,
                            0L,
                            B,
                            hash('f'),
                            childBlueId,
                            childBlueId,
                            Collections.singletonList(sourceEvent),
                            4L);
            ManagedRevisionCause cause = ClosureEvidenceFactory
                    .managedRevisionCause(
                            historical.occurrenceIdentity(),
                            0L,
                            1L,
                            child,
                            sourceReceipt);
            ExecutionPolicy policy = ClosureEvidenceFactory.executionPolicy(
                    GENEROUS_GAS,
                    Collections.<DocumentId, Long>emptyMap(),
                    "event-only-managed-revision-gas-v1");
            ClosureInvocationInput input = ClosureEvidenceFactory
                    .processClosure(
                            snapshot,
                            cause,
                            Collections.<DirectLogicalDelivery>emptyList(),
                            policy,
                            environment);

            ClosureAttemptResult attempt;
            try (BlueClosureContracts contracts =
                         new BlueClosureContracts(owner)) {
                attempt = contracts.processClosure(input);
            }

            assertTrue(attempt.isComplete());
            ClosureProcessResult result = attempt.processResult();
            assertSuccess(result);
            assertEquals(1, result.managedTransitionReceipts().size());
            ManagedDocumentTransitionReceipt receipt = receipt(result, A);
            assertEquals(receipt.beforeBlueId(), receipt.afterBlueId());
            assertEquals(1, receipt.emittedRootEvents().size());
            assertEquals(blueId(EVENT_PUBLIC),
                    receipt.emittedRootEvents().get(0).eventBlueId());
            assertEquals(Collections.singletonList(blueId(EVENT_PUBLIC)),
                    eventBlueIds(result.publicEvents()));
            assertEquals(result.totalGas(),
                    managedTransitionGas(result));
            ManagedOccurrenceBinding activated = result
                    .occurrenceBindings().get(0);
            assertTrue(activated.active());
            assertNull(activated.pendingHistoricalEpoch());
            assertEquals(1, probe.executionCount);
        }
    }

    @Test
    void requirement06InitializationPatchContinuesThroughDocumentUpdate() {
        ProbeProcessor probe = new ProbeProcessor();
        try (DocumentProcessor owner = owner(probe)) {
            Node body = new Node()
                    .name("Initialization Document Update Root")
                    .properties("source", new Node().value("before"))
                    .properties("handled", new Node().value(Boolean.FALSE))
                    .contracts(new Node()
                            .properties("lifecycle", lifecycleChannel())
                            .properties("updates", updateChannel("/source"))
                            .properties("patchSource", handler("lifecycle"))
                            .properties("handleUpdate", handler("updates")));
            ClosureInvocationInput input = simpleAdmission(
                    owner, A, body, true, GENEROUS_GAS);
            Capture capture = new Capture();

            ClosureProcessResult result = full(owner, input, capture)
                    .processResult();

            assertSuccess(result);
            Node admitted = document(result, A).document();
            assertEquals("after", admitted.getAsText("/source"));
            assertTrue((Boolean) admitted.get("/handled"));
            assertTrue(workKinds(capture.evidence)
                    .contains(WorkKind.DOCUMENT_UPDATE));
        }
    }

    @Test
    void managedCurrentDeliveryDoesNotRouteThroughContractsItCreates() {
        ProbeProcessor probe = new ProbeProcessor();
        try (DocumentProcessor owner = owner(probe)) {
            Node body = new Node()
                    .name("Frozen added update surface Root")
                    .properties(
                            "createdRouteRan",
                            new Node().value(Boolean.FALSE))
                    .contracts(new Node()
                            .properties("lifecycle", lifecycleChannel())
                            .properties(
                                    "installFutureUpdateSurface",
                                    handler("lifecycle")));
            ClosureInvocationInput input = simpleAdmission(
                    owner, A, body, true, GENEROUS_GAS);
            Capture capture = new Capture();

            ClosureProcessResult result = full(owner, input, capture)
                    .processResult();

            assertSuccess(result);
            Node admitted = document(result, A).document();
            assertFalse((Boolean) admitted.get("/createdRouteRan"));
            assertNotNull(admitted.get("/contracts/createdUpdates"));
            assertNotNull(admitted.get("/contracts/createdUpdateHandler"));
            assertEquals(0L, countKind(
                    capture.evidence, WorkKind.DOCUMENT_UPDATE));
        }
    }

    @Test
    void managedLaterTransitionUsesSurfaceCreatedByPriorTransition() {
        ProbeProcessor probe = new ProbeProcessor();
        try (DocumentProcessor owner = owner(probe)) {
            Node body = new Node()
                    .name("Live later update surface Root")
                    .properties(
                            "laterSignal",
                            new Node().value(Boolean.FALSE))
                    .properties(
                            "futureRouteRuns",
                            new Node().value(BigInteger.ZERO))
                    .contracts(new Node()
                            .properties("lifecycle", lifecycleChannel())
                            .properties(
                                    "installThenUseFutureUpdateSurface",
                                    handler("lifecycle")));
            ClosureInvocationInput input = simpleAdmission(
                    owner, A, body, true, GENEROUS_GAS);
            Capture capture = new Capture();

            ClosureProcessResult result = full(owner, input, capture)
                    .processResult();

            assertSuccess(result);
            Node admitted = document(result, A).document();
            assertTrue((Boolean) admitted.get("/laterSignal"));
            assertEquals(BigInteger.ONE,
                    admitted.get("/futureRouteRuns"));
            assertEquals(1L, countKind(
                    capture.evidence, WorkKind.DOCUMENT_UPDATE));
        }
    }

    @Test
    void managedCurrentDeliveryCompletesRemovedRouteOnceThenUsesNewSurface() {
        ProbeProcessor probe = new ProbeProcessor();
        try (DocumentProcessor owner = owner(probe)) {
            Node body = new Node()
                    .name("Frozen removed update surface Root")
                    .properties(
                            "removedRouteRuns",
                            new Node().value(BigInteger.ZERO))
                    .contracts(new Node()
                            .properties("lifecycle", lifecycleChannel())
                            .properties("updates", updateChannel("/contracts"))
                            .properties(
                                    "removeCurrentUpdateSurface",
                                    handler("lifecycle"))
                            .properties(
                                    "handleRemovedUpdate",
                                    handler("updates")));
            ClosureInvocationInput input = simpleAdmission(
                    owner, A, body, true, GENEROUS_GAS);
            Capture capture = new Capture();

            ClosureProcessResult result = full(owner, input, capture)
                    .processResult();

            assertSuccess(result);
            Node admitted = document(result, A).document();
            assertEquals(BigInteger.ONE,
                    admitted.get("/removedRouteRuns"));
            Node contracts = admitted.getContracts();
            assertNull(contracts.getProperties().get("updates"));
            assertNull(contracts.getProperties().get(
                    "handleRemovedUpdate"));
            assertNull(contracts.getProperties().get(
                    "removeCurrentUpdateSurface"));
            assertEquals(1L, countKind(
                    capture.evidence, WorkKind.DOCUMENT_UPDATE));
        }
    }

    @Test
    void requirement07TerminationCommitsAcyclicStateAndCyclicReceipt() {
        ProbeProcessor acyclicProbe = new ProbeProcessor();
        try (DocumentProcessor owner = owner(acyclicProbe)) {
            Node body = terminatingDocument("Acyclic terminating Root");
            ClosureInvocationInput input = simpleAdmission(
                    owner, A, body, true, GENEROUS_GAS);

            ClosureProcessResult result = full(owner, input, null)
                    .processResult();

            assertSuccess(result);
            ResultingDocument terminated = document(result, A);
            Node expected = body.clone();
            installTerminatedMarker(
                    expected, "completed", "initialization");
            String expectedAfter = blueId(expected);
            assertTrue(terminated.terminated());
            assertFalse(terminated.initialized());
            assertEquals(expectedAfter, terminated.afterBlueId());
            assertNodeEquals(expected, terminated.document());
            assertNull(NodePathEditor.getOrNull(
                    terminated.document(), "/contracts/initialized"));
            assertTrue(result.checkpointWrites().isEmpty());
            ClosureCommitCompanion.DocumentDelta committed = result
                    .commitCompanion().resultingDocuments().get(0);
            assertEquals(A, committed.documentId());
            assertEquals(blueId(body), committed.beforeBlueId());
            assertEquals(expectedAfter, committed.afterBlueId());
        }

        ProbeProcessor cyclicProbe = new ProbeProcessor();
        try (DocumentProcessor owner = owner(cyclicProbe)) {
            ClosureEnvironment environment = environment(owner);
            Node placeholder = terminatingSelfCycle();
            CyclicFixture fixture = selfCycle(
                    environment, placeholder, true);
            ClosureInvocationInput input = admission(
                    fixture.snapshot, environment, GENEROUS_GAS);
            Capture capture = new Capture();

            ClosureProcessResult result = full(owner, input, capture)
                    .processResult();

            assertSuccess(result);
            CyclicSetFinalization initialExpected =
                    new CircularSetIdentityCalculator()
                            .finalizeCyclicSet(Collections.singletonList(
                                    placeholder));
            Node expectedPlaceholder = placeholder.clone();
            installTerminatedMarker(
                    expectedPlaceholder, "completed", "initialization");
            CyclicSetFinalization expected =
                    new CircularSetIdentityCalculator()
                            .finalizeCyclicSet(Collections.singletonList(
                                    expectedPlaceholder));
            ResultingDocument terminated = document(result, A);
            assertTrue(terminated.terminated());
            assertFalse(terminated.initialized());
            assertEquals(expected.membersInInputOrder().get(0)
                    .finalBlueId(), terminated.afterBlueId());
            List<TentativeFinalization> receipts = capture.evidence
                    .tentativeFinalizations();
            assertEquals(2, receipts.size());
            TentativeFinalization initialReceipt = receipts.get(0);
            assertEquals(TentativeFinalization.Boundary.Kind.WORK,
                    initialReceipt.boundary().kind());
            assertEquals(Long.valueOf(0L),
                    initialReceipt.boundary().afterWorkOrdinal());
            assertEquals(initialExpected.masterBlueId(),
                    initialReceipt.masterBlueId());
            assertEquals(Collections.singletonMap(
                            A,
                            initialExpected.membersInInputOrder().get(0)
                                    .finalBlueId()),
                    initialReceipt.memberBlueIds());
            assertEquals(new CyclicCanonicalLimitProjection()
                            .project(initialExpected).canonicalBytes(),
                    initialReceipt.canonicalBytes());
            TentativeFinalization receipt = receipts.get(1);
            assertEquals(TentativeFinalization.Boundary.Kind
                            .TERMINATION_MARKER,
                    receipt.boundary().kind());
            assertEquals(Long.valueOf(2L),
                    receipt.boundary().afterWorkOrdinal());
            assertEquals(expected.masterBlueId(), receipt.masterBlueId());
            assertEquals(Collections.singletonMap(
                            A,
                            expected.membersInInputOrder().get(0)
                                    .finalBlueId()),
                    receipt.memberBlueIds());
            assertEquals(new CyclicCanonicalLimitProjection()
                            .project(expected).canonicalBytes(),
                    receipt.canonicalBytes());
        }
    }

    @Test
    void requirement08CanonicalInitializationIgnoresInputMapOrder() {
        ProbeProcessor forwardProbe = new ProbeProcessor();
        ProbeProcessor reverseProbe = new ProbeProcessor();
        try (DocumentProcessor forwardOwner = owner(forwardProbe);
             DocumentProcessor reverseOwner = owner(reverseProbe)) {
            Node child = recordingDocument("b");
            String childBlueId = blueId(child);
            Node parent = recordingDocument("a")
                    .properties("child", new Node().blueId(childBlueId));
            parent.getContracts().properties(
                    "embedded", processEmbedded("/child"));
            LinkedHashMap<DocumentId, Node> forward =
                    new LinkedHashMap<DocumentId, Node>();
            forward.put(A, parent.clone());
            forward.put(B, child.clone());
            LinkedHashMap<DocumentId, Node> reverse =
                    new LinkedHashMap<DocumentId, Node>();
            reverse.put(B, child.clone());
            reverse.put(A, parent.clone());
            ClosureEnvironment forwardEnvironment =
                    environment(forwardOwner);
            ClosureEnvironment reverseEnvironment =
                    environment(reverseOwner);
            ManagedOccurrenceBinding forwardBinding =
                    ManagedOccurrenceBinding.derived(
                            forwardEnvironment.managedBindingPolicyIdentity(),
                            A,
                            ScopeAddress.embedded("/child", 1L),
                            B,
                            childBlueId,
                            true,
                            null);
            ManagedOccurrenceBinding reverseBinding =
                    ManagedOccurrenceBinding.derived(
                            reverseEnvironment.managedBindingPolicyIdentity(),
                            A,
                            ScopeAddress.embedded("/child", 1L),
                            B,
                            childBlueId,
                            true,
                            null);
            ClosureInvocationInput forwardInput = admission(
                    finalizedSnapshot(
                            forward,
                            Collections.singletonList(forwardBinding),
                            Collections.singletonList(A)),
                    forwardEnvironment,
                    GENEROUS_GAS);
            ClosureInvocationInput reverseInput = admission(
                    finalizedSnapshot(
                            reverse,
                            Collections.singletonList(reverseBinding),
                            Collections.singletonList(A)),
                    reverseEnvironment,
                    GENEROUS_GAS);
            Capture forwardCapture = new Capture();
            Capture reverseCapture = new Capture();

            ClosureProcessResult forwardResult = full(
                    forwardOwner, forwardInput, forwardCapture)
                    .processResult();
            ClosureProcessResult reverseResult = full(
                    reverseOwner, reverseInput, reverseCapture)
                    .processResult();

            assertSuccess(forwardResult);
            assertSuccess(reverseResult);
            assertEquals(forwardInput.invocationIdentity(),
                    reverseInput.invocationIdentity());
            assertEquals(Arrays.asList("b", "a"),
                    forwardProbe.initializationOrder);
            assertEquals(Arrays.asList("b", "a"),
                    reverseProbe.initializationOrder);
            assertExactParity(forwardResult, reverseResult);
            assertEquals(workIdentities(forwardCapture.evidence),
                    workIdentities(reverseCapture.evidence));
        }
    }

    @Test
    void requirement09FiniteCyclicInitializationRouteQuiesces() {
        ProbeProcessor probe = new ProbeProcessor();
        try (DocumentProcessor owner = owner(probe)) {
            ClosureEnvironment environment = environment(owner);
            CyclicFixture fixture = twoMemberCycle(
                    environment,
                    finiteCycleA(),
                    finiteCycleB(),
                    true,
                    false);
            ClosureInvocationInput input = admission(
                    fixture.snapshot, environment, GENEROUS_GAS);
            Capture capture = new Capture();

            ClosureProcessResult result = full(owner, input, capture)
                    .processResult();

            assertSuccess(result);
            assertTrue((Boolean) document(result, A).document()
                    .get("/finished"));
            assertEquals(Collections.singletonList(EVENT_PING_BLUE_ID),
                    eventBlueIds(result.publicEvents()));
            assertEquals(Arrays.asList("ping", "pong"),
                    probe.observedEventKinds);
            assertEquals(2L, countKind(
                    capture.evidence, WorkKind.EMBEDDED_EVENT));
            assertEquals(2, result.managedTransitionReceipts().size());
            assertEquals(A, result.managedTransitionReceipts()
                    .get(0).documentId());
            assertEquals(B, result.managedTransitionReceipts()
                    .get(1).documentId());
            assertEquals(result.totalGas(),
                    managedTransitionGas(result));
            assertTrue(capture.evidence.workTrace().size() < 12,
                    "The finite cycle must reach a small fixed point");
        }
    }

    @Test
    void requirement10InfiniteCyclicRouteExhaustsSharedGasAndRollsBack() {
        ProbeProcessor probe = new ProbeProcessor();
        try (DocumentProcessor owner = owner(probe)) {
            ClosureEnvironment environment = environment(owner);
            CyclicFixture fixture = twoMemberCycle(
                    environment,
                    loopingCycleA(),
                    loopingCycleB(),
                    true,
                    false);
            ClosureInvocationInput input = admission(
                    fixture.snapshot, environment, LOOP_GAS);
            Capture capture = new Capture();

            ClosureProcessResult result = full(owner, input, capture)
                    .processResult();

            assertEquals(ProcessorStatus.GAS_LIMIT_EXCEEDED,
                    result.status(), diagnostic(result));
            assertLiteralRollback(input, result);
            assertNotNull(result.rejectedCharge());
            assertEquals(RejectedCharge.ApplicableCap.Kind.SHARED,
                    result.rejectedCharge().applicableCap().kind());
            assertTrue(result.totalGas() <= LOOP_GAS);
            assertTrue(countKind(capture.evidence,
                    WorkKind.EMBEDDED_EVENT) > 2L);
            assertEquals(GAS_FAILURE_ORACLE,
                    gasFailureProjection(result, capture.evidence));
            assertTrue(result.publicEvents().isEmpty());
            assertNull(result.commitCompanion());
        }
    }

    @Test
    void requirement11LaterMemberFailureRollsBackWholeClosure() {
        ProbeProcessor probe = new ProbeProcessor();
        try (DocumentProcessor owner = owner(probe)) {
            Node first = new Node()
                    .name("First admission member")
                    .properties("first", new Node().value(Boolean.FALSE))
                    .contracts(new Node()
                            .properties("lifecycle", lifecycleChannel())
                            .properties("firstPatch", handler("lifecycle")));
            Node later = new Node()
                    .name("Later failing admission member")
                    .contracts(new Node()
                            .properties("lifecycle", lifecycleChannel())
                            .properties("laterFail", handler("lifecycle")));
            ClosureEnvironment environment = environment(owner);
            ClosureInvocationInput input = admission(
                    finalizedSnapshot(
                            bodies(A, first, B, later),
                            Collections.<ManagedOccurrenceBinding>emptyList(),
                            Arrays.asList(A, B)),
                    environment,
                    GENEROUS_GAS);

            ClosureProcessResult result = full(owner, input, null)
                    .processResult();

            assertEquals(ProcessorStatus.RUNTIME_FATAL,
                    result.status(), diagnostic(result));
            assertEquals(ProcessorErrorCategory.RuntimeExecutionFailure,
                    result.diagnostic().category());
            assertLiteralRollback(input, result);
            assertFalse(document(result, A).initialized());
            assertNull(NodePathEditor.getOrNull(
                    document(result, A).document(),
                    "/contracts/initialized"));
            assertEquals(Boolean.FALSE, document(result, A).document()
                    .get("/first"));
        }
    }

    @Test
    void requirement12InlineAndPureReferenceCyclicInputsAreExactParity() {
        ProbeProcessor referenceProbe = new ProbeProcessor();
        ProbeProcessor inlineProbe = new ProbeProcessor();
        try (DocumentProcessor referenceOwner = owner(referenceProbe);
             DocumentProcessor inlineOwner = owner(inlineProbe)) {
            ClosureEnvironment referenceEnvironment =
                    environment(referenceOwner);
            ClosureEnvironment inlineEnvironment =
                    environment(inlineOwner);
            CyclicFixture referenceFixture = twoMemberCycle(
                    referenceEnvironment,
                    quietCycleA(),
                    quietCycleB(),
                    true,
                    false);
            CyclicFixture inlineFixture = twoMemberCycle(
                    inlineEnvironment,
                    quietCycleA(),
                    quietCycleB(),
                    true,
                    true);
            ClosureInvocationInput referenceInput = admission(
                    referenceFixture.snapshot,
                    referenceEnvironment,
                    GENEROUS_GAS);
            ClosureInvocationInput inlineInput = admission(
                    inlineFixture.snapshot,
                    inlineEnvironment,
                    GENEROUS_GAS);
            Capture referenceCapture = new Capture();
            Capture inlineCapture = new Capture();

            ClosureProcessResult referenceResult = full(
                    referenceOwner, referenceInput, referenceCapture)
                    .processResult();
            ClosureProcessResult inlineResult = full(
                    inlineOwner, inlineInput, inlineCapture)
                    .processResult();

            assertSuccess(referenceResult);
            assertSuccess(inlineResult);
            assertEquals(referenceInput.invocationIdentity(),
                    inlineInput.invocationIdentity(),
                    "Verified cyclic representation is not identity-bearing");
            assertExactParity(referenceResult, inlineResult);
            assertEquals(workIdentities(referenceCapture.evidence),
                    workIdentities(inlineCapture.evidence));
            assertEquals(finalizationProjection(referenceCapture.evidence),
                    finalizationProjection(inlineCapture.evidence));
        }
    }

    @Test
    void requirement13ColdAndWarmExactNodeRunsHaveExactParity() {
        ProbeProcessor probe = new ProbeProcessor();
        CountingNodeProvider provider = new CountingNodeProvider();
        ContractProcessorRegistry registry =
                ContractProcessorRegistryBuilder.create()
                        .register(
                                HANDLER_BLUE_ID,
                                HANDLER_TYPE,
                                probe)
                        .build();
        try (BlueLanguage language = BlueLanguage.builder()
                     .nodeProvider(new TestNodeProvider(provider))
                     .build();
             BlueContracts backingContracts = BlueContracts.builder(
                             language.processing())
                     .runtimeRegistry(registry)
                     .build();
             DocumentProcessor owner = DocumentProcessor.builder()
                     .runtimeRegistry(registry)
                     .runtimeRegistryIdentity(
                             registry.generationIdentity())
                     .runtimeAccess(backingContracts.runtimeAccess())
                     .build()) {
            ClosureEnvironment environment = environment(owner);
            CyclicFixture fixture = twoMemberCycle(
                    environment,
                    exactQuietCycleA(),
                    exactQuietCycleB(),
                    true,
                    false);
            ClosureInvocationInput input = admission(
                    fixture.snapshot, environment, GENEROUS_GAS);
            Captures captures = new Captures();
            ClosureProcessResult cold;
            ClosureProcessResult warm;

            try (BlueClosureContracts contracts =
                         new BlueClosureContracts(owner, captures)) {
                ClosureAttemptResult coldAttempt =
                        contracts.admitClosureWithLifecycleQueue(input);
                assertTrue(coldAttempt.isComplete(),
                        "Cold exact-node run suspended: "
                                + coldAttempt.requiredExactBlueIds()
                                + "; exactType=" + EXACT_DOCUMENT_TYPE_BLUE_ID
                                + "; dictionary="
                                + BlueLanguageConstants.DICTIONARY_TYPE_BLUE_ID);
                cold = coldAttempt.processResult();
                int coldFetches = provider.exactFetches;
                assertTrue(coldFetches > 0,
                        "The first admission must establish the exact type");
                int warmedCacheEntries = owner.administration()
                        .cacheEntryCount();
                assertTrue(warmedCacheEntries > 0,
                        "The first admission must warm processor-owned caches");
                provider.exactFetches = 0;
                ClosureAttemptResult warmAttempt =
                        contracts.admitClosureWithLifecycleQueue(input);
                assertTrue(warmAttempt.isComplete(),
                        "Warm exact-node run suspended: "
                                + warmAttempt.requiredExactBlueIds());
                warm = warmAttempt.processResult();
                assertTrue(provider.exactFetches < coldFetches,
                        "The warm run must perform fewer exact provider "
                                + "lookups: cold=" + coldFetches
                                + ", warm=" + provider.exactFetches);
                assertEquals(warmedCacheEntries, owner.administration()
                                .cacheEntryCount(),
                        "The warm admission must reuse the populated caches");
            }

            assertSuccess(cold);
            assertSuccess(warm);
            assertEquals(2, captures.values.size());
            assertExactParity(cold, warm);
            assertEquals(workIdentities(captures.values.get(0)),
                    workIdentities(captures.values.get(1)));
            assertEquals(finalizationProjection(captures.values.get(0)),
                    finalizationProjection(captures.values.get(1)));
        }
    }

    @Test
    void requirement14RetryReproducesGasFailureEvidenceIdentity() {
        ProbeProcessor probe = new ProbeProcessor();
        try (DocumentProcessor owner = owner(probe)) {
            ClosureEnvironment environment = environment(owner);
            CyclicFixture fixture = twoMemberCycle(
                    environment,
                    loopingCycleA(),
                    loopingCycleB(),
                    true,
                    false);
            ClosureInvocationInput input = admission(
                    fixture.snapshot, environment, LOOP_GAS);
            Captures captures = new Captures();
            ClosureProcessResult first;
            ClosureProcessResult retry;

            try (BlueClosureContracts contracts =
                         new BlueClosureContracts(owner, captures)) {
                first = contracts.admitClosureWithLifecycleQueue(input)
                        .processResult();
                retry = contracts.admitClosureWithLifecycleQueue(input)
                        .processResult();
            }

            assertEquals(ProcessorStatus.GAS_LIMIT_EXCEEDED,
                    first.status());
            assertEquals(first.invocationIdentity(),
                    retry.invocationIdentity());
            assertEquals(first.status(), retry.status());
            assertEquals(first.diagnostic().category(),
                    retry.diagnostic().category());
            assertEquals(first.diagnostic().message(),
                    retry.diagnostic().message());
            assertEquals(first.diagnostic().details(),
                    retry.diagnostic().details());
            assertEquals(first.totalGas(), retry.totalGas());
            assertEquals(first.gasTraceIdentity(),
                    retry.gasTraceIdentity());
            assertEquals(rejectedChargeProjection(first.rejectedCharge()),
                    rejectedChargeProjection(retry.rejectedCharge()));
            assertEquals(workIdentities(captures.values.get(0)),
                    workIdentities(captures.values.get(1)));
            assertEquals(GAS_FAILURE_ORACLE,
                    gasFailureProjection(first, captures.values.get(0)));
            assertLiteralRollback(input, first);
            assertLiteralRollback(input, retry);
        }
    }

    @Test
    void requirement15AdmissionWritesNoTimelineCheckpoint() {
        ProbeProcessor probe = new ProbeProcessor();
        try (DocumentProcessor owner = owner(probe)) {
            Node body = new Node()
                    .name("No Timeline checkpoint Root")
                    .contracts(new Node()
                            .properties("lifecycle", lifecycleChannel())
                            .properties("initPatch", handler("lifecycle")));
            ClosureInvocationInput input = simpleAdmission(
                    owner, A, body, true, GENEROUS_GAS);

            ClosureProcessResult result = full(owner, input, null)
                    .processResult();

            assertSuccess(result);
            assertTrue(result.checkpointWrites().isEmpty());
            assertEquals(result.checkpointWritesIdentity(),
                    result.commitCompanion().checkpointWritesIdentity());
            assertNull(NodePathEditor.getOrNull(
                    document(result, A).document(),
                    "/contracts/checkpoint"));
        }
    }

    @Test
    void requirement16UnknownInitializationOccurrenceIsNoncommitting() {
        ProbeProcessor probe = new ProbeProcessor();
        try (DocumentProcessor owner = owner(probe)) {
            Node body = new Node()
                    .name("Unknown initialization occurrence Root")
                    .contracts(new Node()
                            .properties("embedded", processEmbedded("/child"))
                            .properties("lifecycle", lifecycleChannel())
                            .properties("installUnknown",
                                    handler("lifecycle")));
            ClosureInvocationInput input = simpleAdmission(
                    owner, A, body, true, GENEROUS_GAS);
            Capture capture = new Capture();
            ClosureAttemptResult attempt;
            try (BlueClosureContracts contracts =
                         new BlueClosureContracts(owner, capture)) {
                attempt = contracts.admitClosureWithLifecycleQueue(input);
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
            assertEquals(A, demand.sourceDocumentId());
            assertEquals("/child", demand.sourcePath());
            assertNull(capture.evidence,
                    "A resource demand must not publish completion evidence");
            assertNull(NodePathEditor.getOrNull(
                    input.snapshot().managedDocument(A).document(),
                    "/child"));
            assertTrue(input.snapshot().occurrences().isEmpty());
        }
    }

    @Test
    void requirement16aInitialMissingExactReferenceSuspendsBeforeLifecycleWork() {
        ProbeProcessor probe = new ProbeProcessor();
        try (DocumentProcessor owner = owner(probe)) {
            String missingBlueId = blueId(
                    new Node().name("Unavailable initial peer"));
            Node body = new Node()
                    .name("Initial missing exact peer Root")
                    .properties("peer", new Node().blueId(missingBlueId))
                    .contracts(new Node().properties(
                            "embedded", processEmbedded("/peer")));
            ClosureInvocationInput input = simpleAdmission(
                    owner, A, body, true, GENEROUS_GAS);

            assertInitialResourceDemandPreflight(
                    owner,
                    probe,
                    input,
                    ExactNodeDemand.class,
                    missingBlueId,
                    Collections.singletonList(missingBlueId));
        }
    }

    @Test
    void requirement16bInitialKnownPeerWithoutRowSuspendsBeforeLifecycleWork() {
        ProbeProcessor probe = new ProbeProcessor();
        try (DocumentProcessor owner = owner(probe)) {
            Node target = new Node().name("Known initial peer target");
            String targetBlueId = blueId(target);
            Node source = new Node()
                    .name("Initial peer without occurrence evidence Root")
                    .properties("peer", new Node().blueId(targetBlueId))
                    .contracts(new Node().properties(
                            "embedded", processEmbedded("/peer")));
            ClosureInvocationInput input = admission(
                    finalizedSnapshot(
                            bodies(A, source, B, target),
                            Collections.<ManagedOccurrenceBinding>emptyList(),
                            Collections.singletonList(A)),
                    environment(owner),
                    GENEROUS_GAS);
            assertEquals(targetBlueId,
                    input.snapshot().managedDocument(B).blueId());

            assertInitialResourceDemandPreflight(
                    owner,
                    probe,
                    input,
                    ManagedOccurrenceEvidenceDemand.class,
                    targetBlueId,
                    Collections.<String>emptyList());
        }
    }

    @Test
    void requirement17ExistingAdmitClosureRemainsExplicitlyBounded() {
        ProbeProcessor probe = new ProbeProcessor();
        try (DocumentProcessor owner = owner(probe)) {
            Node body = new Node()
                    .name("Bounded compatibility Root")
                    .properties("reaction", new Node().value("none"))
                    .contracts(new Node()
                            .properties("lifecycle", lifecycleChannel())
                            .properties("triggered",
                                    triggeredChannel(EVENT_ONE_BLUE_ID))
                            .properties("emitOne", handler("lifecycle"))
                            .properties("reactOne", handler("triggered")));
            ClosureInvocationInput input = simpleAdmission(
                    owner, A, body, true, GENEROUS_GAS);

            ClosureProcessResult result;
            try (BlueClosureContracts contracts =
                         new BlueClosureContracts(owner)) {
                result = contracts.admitClosure(input).processResult();
            }

            assertEquals(ProcessorStatus.RUNTIME_FATAL,
                    result.status(), diagnostic(result));
            assertEquals(ProcessorErrorCategory.RuntimeExecutionFailure,
                    result.diagnostic().category());
            assertEquals(
                    "Initialization-caused application events require the full event queue lane",
                    result.diagnostic().message());
            assertLiteralRollback(input, result);
        }
    }

    @Test
    void requirement18InitializationReactivationDoesNotSeedDocumentTwice() {
        ProbeProcessor probe = new ProbeProcessor();
        try (DocumentProcessor owner = owner(probe)) {
            ClosureEnvironment environment = environment(owner);
            Node body = new Node()
                    .name("Prospective self reactivation Root")
                    .contracts(new Node()
                            .properties("embedded", processEmbedded("/self"))
                            .properties("lifecycle", lifecycleChannel())
                            .properties(
                                    "reactivateSelf",
                                    handler(
                                            "lifecycle",
                                            initiatedPattern())));
            String initialBlueId = blueId(body);
            ManagedOccurrenceBinding prospective =
                    ManagedOccurrenceBinding.derived(
                            environment.managedBindingPolicyIdentity(),
                            A,
                            ScopeAddress.embedded("/self", 1L),
                            A,
                            initialBlueId,
                            false,
                            null);
            AffectedClosureSnapshot snapshot = finalizedSnapshot(
                    Collections.singletonMap(A, body),
                    Collections.singletonList(prospective),
                    Collections.singletonList(A));
            probe.reactivationTargetBlueId = snapshot
                    .managedDocument(A).blueId();
            ClosureInvocationInput input = admission(
                    snapshot, environment, GENEROUS_GAS);
            Capture capture = new Capture();

            ClosureProcessResult result = full(owner, input, capture)
                    .processResult();

            assertSuccess(result);
            assertEquals(1, probe.reactivationInitializationCount);
            assertEquals(1L, capture.evidence.workTrace().stream()
                    .filter(work -> work.kind() == WorkKind.INITIALIZATION)
                    .filter(work -> A.equals(work.targetDocumentId()))
                    .count());
            assertTrue(document(result, A).initialized());
            assertNotNull(NodePathEditor.getOrNull(
                    document(result, A).document(), "/self"));
        }
    }

    @Test
    void activatedDormantProspectiveTargetInitializesExactlyOnceBeforeCommit() {
        ProbeProcessor probe = new ProbeProcessor();
        try (DocumentProcessor owner = owner(probe)) {
            ClosureEnvironment environment = environment(owner);
            Node source = new Node()
                    .name("Dormant activation source")
                    .contracts(new Node()
                            .properties("lifecycle", lifecycleChannel())
                            .properties("activateDormant",
                                    handler(
                                            "lifecycle",
                                            initiatedPattern())));
            Node target = new Node()
                    .name("Dormant activation target")
                    .contracts(new Node()
                            .properties("lifecycle", lifecycleChannel())
                            .properties("recordDormantInit",
                                    handler(
                                            "lifecycle",
                                            initiatedPattern())));
            String targetBlueId = blueId(target);
            ManagedOccurrenceBinding prospective =
                    ManagedOccurrenceBinding.derived(
                            environment.managedBindingPolicyIdentity(),
                            A,
                            ScopeAddress.embedded("/reserved", 1L),
                            B,
                            targetBlueId,
                            false,
                            null);
            AffectedClosureSnapshot snapshot = finalizedSnapshot(
                    bodies(A, source, B, target),
                    Collections.singletonList(prospective),
                    Collections.singletonList(A));
            probe.dormantActivationTargetBlueId = snapshot
                    .managedDocument(B).blueId();
            ClosureInvocationInput input = admission(
                    snapshot, environment, GENEROUS_GAS);
            Capture capture = new Capture();

            ClosureProcessResult result = full(owner, input, capture)
                    .processResult();

            assertSuccess(result);
            assertTrue(document(result, A).initialized());
            assertTrue(document(result, B).initialized());
            assertEquals(1, probe.dormantTargetInitializationCount);
            assertEquals(1L, capture.evidence.workTrace().stream()
                    .filter(work -> work.kind() == WorkKind.INITIALIZATION)
                    .filter(work -> B.equals(work.targetDocumentId()))
                    .count());
            assertTrue(result.occurrenceBindings().stream()
                    .anyMatch(binding -> A.equals(
                                    binding.sourceDocumentId())
                            && "/reserved".equals(binding.sourcePath())
                            && binding.active()
                            && binding.pendingHistoricalEpoch() == null));
        }
    }

    @Test
    void requirement19NestedUpdateEventWaitsForParentNextPatch() {
        ProbeProcessor probe = new ProbeProcessor();
        try (DocumentProcessor owner = owner(probe)) {
            Node body = new Node()
                    .name("Nested update event ordering Root")
                    .properties("source", new Node().value("before"))
                    .properties(
                            "parentFinished",
                            new Node().value(Boolean.FALSE))
                    .properties(
                            "observedParentFinished",
                            new Node().value(Boolean.FALSE))
                    .contracts(new Node()
                            .properties("lifecycle", lifecycleChannel())
                            .properties("updates", updateChannel("/source"))
                            .properties(
                                    "triggered",
                                    triggeredChannel(EVENT_ONE_BLUE_ID))
                            .properties(
                                    "parentPatchThenFinish",
                                    handler(
                                            "lifecycle",
                                            initiatedPattern()))
                            .properties(
                                    "nestedEmitOne",
                                    handler("updates"))
                            .properties(
                                    "observeParentFinished",
                                    handler("triggered")));
            ClosureInvocationInput input = simpleAdmission(
                    owner, A, body, true, GENEROUS_GAS);
            Capture capture = new Capture();

            ClosureProcessResult result = full(owner, input, capture)
                    .processResult();

            assertSuccess(result);
            Node admitted = document(result, A).document();
            assertTrue((Boolean) admitted.get("/parentFinished"));
            assertTrue((Boolean) admitted.get("/observedParentFinished"));
            ClosureWorkOccurrence update = onlyWork(
                    capture.evidence, WorkKind.DOCUMENT_UPDATE);
            ClosureWorkOccurrence triggered = onlyWork(
                    capture.evidence, WorkKind.TRIGGERED_EVENT);
            GasTraceEntry enqueue = onlyCharge(
                    result, "internalEventEnqueued");
            assertEquals(update.workIdentity(), enqueue.workOccurrenceId(),
                    "The nested event is caused by the Document Update work");
            assertEquals(1, result.publicEvents().size());
            assertEquals(result.publicEvents().get(0)
                            .eventOccurrenceIdentity(),
                    triggered.sourceOccurrenceIdentity());
        }
    }

    @Test
    void requirement20NestedUpdateTerminationWaitsForParentNextPatch() {
        ProbeProcessor probe = new ProbeProcessor();
        try (DocumentProcessor owner = owner(probe)) {
            Node body = new Node()
                    .name("Nested update termination ordering Root")
                    .properties("source", new Node().value("before"))
                    .properties(
                            "parentFinished",
                            new Node().value(Boolean.FALSE))
                    .properties(
                            "terminationObservedParentFinished",
                            new Node().value(Boolean.FALSE))
                    .contracts(new Node()
                            .properties("lifecycle", lifecycleChannel())
                            .properties("updates", updateChannel("/source"))
                            .properties(
                                    "parentPatchThenFinish",
                                    handler(
                                            "lifecycle",
                                            initiatedPattern()))
                            .properties(
                                    "nestedTerminate",
                                    handler("updates"))
                            .properties(
                                    "observeTerminationParentFinished",
                                    handler(
                                            "lifecycle",
                                            terminatedPattern())));
            ClosureInvocationInput input = simpleAdmission(
                    owner, A, body, true, GENEROUS_GAS);
            Capture capture = new Capture();

            ClosureProcessResult result = full(owner, input, capture)
                    .processResult();

            assertSuccess(result);
            ResultingDocument admitted = document(result, A);
            assertTrue(admitted.terminated());
            assertFalse(admitted.initialized());
            assertTrue((Boolean) admitted.document()
                    .get("/parentFinished"));
            assertTrue((Boolean) admitted.document()
                    .get("/terminationObservedParentFinished"));
            assertEquals("nested-update", admitted.document()
                    .getAsText("/contracts/terminated/cause"));
            assertEquals("parent-suspended", admitted.document()
                    .getAsText("/contracts/terminated/reason"));
            assertEquals(1L, countKind(
                    capture.evidence, WorkKind.DOCUMENT_UPDATE));
        }
    }

    private static Node retainedNestedSource(
            DocumentProcessor owner, Map<String, Node> exactNodes, String label) {
        Node source = recordingDocument(label)
                .properties("revision", new Node().value(BigInteger.ZERO));
        source.getContracts().properties("ownerChannel",
                typed(RETAINED_SOURCE_CHANNEL_BLUE_ID));
        String authoredBlueId = blueId(source);
        exactNodes.put(authoredBlueId, source.clone());
        installInitializedMarker(source, authoredBlueId);
        try (ManagedDocumentStepRuntime runtime = new ManagedDocumentStepRuntime(owner)) {
            Node event = new Node().name("retained-" + label + "-checkpoint")
                    .properties("subscriptionKey", new Node().value("retained-source"));
            GasChargeContext context = GasChargeContext.closure(label.toLowerCase(),
                    "/", Long.valueOf(0L), Long.valueOf(1L), "ownerChannel",
                    null, null, "fixture.retained-checkpoint");
            ManagedCheckpointCandidate candidate = runtime.classifyExternalDelivery(
                    source,
                    "ownerChannel",
                    ExactEventIdentityEvidence.verify(
                            null,
                            event,
                            blueId(event),
                            null),
                    context).candidate();
            assertNotNull(candidate);
            exactNodes.put(candidate.domain().blueId(), candidate.domain().exactValue());
            source = runtime.settleCheckpoints(source,
                    Collections.singletonList(new ManagedCheckpointSettlementEntry(
                            candidate, 0L, context)),
                    (key, ordinal) -> context, context).resultingBody();
        }
        assertNotNull(NodePathEditor.getOrNull(source,
                "/contracts/checkpoint/entries/ownerChannel"));
        return source;
    }

    private static ManagedDocumentTransitionReceipt retainedNestedReceipt(
            DocumentId source, int epoch, Node before, Node after, boolean duplicates) {
        String invocation = hash((char) ((B.equals(source) ? '1' : '6') + epoch - 1));
        ArrayList<ManagedRootEventOccurrence> events = new ArrayList<ManagedRootEventOccurrence>();
        if (duplicates) {
            for (int ordinal = 0; ordinal < 2; ordinal++) {
                events.add(new ManagedRootEventOccurrence(ordinal, ordinal, source,
                        ClosureIdentityService.INSTANCE.eventOccurrenceIdentity(
                                invocation, ordinal, blueId(EVENT_CHILD)),
                        exactEvent(EVENT_CHILD, blueId(EVENT_CHILD)), true));
            }
        }
        return ManagedDocumentTransitionReceipt.identified(invocation, 0L, source,
                hash(B.equals(source) ? 'b' : 'c'), blueId(before), blueId(after), events, 7L);
    }

    private static ClosureInvocationInput retainedNestedInvocation(
            AffectedClosureSnapshot snapshot, ClosureEnvironment environment,
            String occurrenceIdentity, long toEpoch, Node after,
            ManagedDocumentTransitionReceipt receipt) {
        return ClosureEvidenceFactory.processClosure(snapshot,
                ClosureEvidenceFactory.managedRevisionCause(occurrenceIdentity,
                        toEpoch - 1L, toEpoch, after, receipt),
                Collections.<DirectLogicalDelivery>emptyList(),
                ClosureEvidenceFactory.executionPolicy(GENEROUS_GAS,
                        Collections.<DocumentId, Long>emptyMap(),
                        "retained-nested-existing-receipt-fixture-v1"),
                environment);
    }

    private static Map<String, Object> retainedNestedValues(Map<String, Node> nodes) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Node> entry : nodes.entrySet()) {
            values.put(entry.getKey(), NodeWireForm.get(entry.getValue(),
                    NodeWireForm.Strategy.SIMPLE));
        }
        return values;
    }

    private static DocumentProcessor owner(ProbeProcessor probe) {
        return owner(probe, null);
    }

    private static DocumentProcessor owner(
            ProbeProcessor probe,
            NodeProvider provider) {
        ContractProcessorRegistry registry =
                ContractProcessorRegistryBuilder.create()
                        .register(
                                HANDLER_BLUE_ID,
                                HANDLER_TYPE,
                                probe)
                        .build();
        DocumentProcessor.Builder builder = DocumentProcessor.builder()
                .runtimeRegistry(registry)
                .nodeProvider(new TestNodeProvider(provider));
        return builder.build();
    }

    private static ClosureEnvironment environment(DocumentProcessor owner) {
        return ClosureEvidenceFactory.environment(
                owner,
                hash('a'),
                hash('b'),
                "full-lifecycle-document-lineage-v1",
                "full-lifecycle-binding-lineage-v1",
                "full-lifecycle-provider-domain-v1",
                "full-lifecycle-external-order-v1",
                "full-lifecycle-portable-limits-v1",
                GasSchedule.contracts10().portableLimits());
    }

    private static ClosureInvocationInput simpleAdmission(
            DocumentProcessor owner,
            DocumentId documentId,
            Node body,
            boolean publicRoot,
            long sharedGas) {
        ClosureEnvironment environment = environment(owner);
        return admission(
                finalizedSnapshot(
                        Collections.singletonMap(documentId, body),
                        Collections.<ManagedOccurrenceBinding>emptyList(),
                        publicRoot
                                ? Collections.singletonList(documentId)
                                : Collections.<DocumentId>emptyList()),
                environment,
                sharedGas);
    }

    private static ClosureInvocationInput admission(
            AffectedClosureSnapshot snapshot,
            ClosureEnvironment environment,
            long sharedGas) {
        AdmissionCause cause = ClosureEvidenceFactory.admissionCause(
                AdmissionKind.TOP_LEVEL_ADMISSION,
                "full-lifecycle-admission",
                null,
                null,
                "full-lifecycle-admission-policy-v1");
        ExecutionPolicy policy = ClosureEvidenceFactory.executionPolicy(
                sharedGas,
                Collections.<DocumentId, Long>emptyMap(),
                "full-lifecycle-shared-gas-v1");
        return ClosureEvidenceFactory.admitClosure(
                snapshot, cause, null, policy, environment);
    }

    private static ClosureAttemptResult full(
            DocumentProcessor owner,
            ClosureInvocationInput input,
            ClosureExecutionObserver observer) {
        ClosureAttemptResult result;
        if (observer == null) {
            try (BlueClosureContracts contracts =
                         new BlueClosureContracts(owner)) {
                result = contracts.admitClosureWithLifecycleQueue(input);
            }
        } else {
            try (BlueClosureContracts contracts =
                         new BlueClosureContracts(owner, observer)) {
                result = contracts.admitClosureWithLifecycleQueue(input);
            }
        }
        assertTrue(result.isComplete(),
                "The fixed test provider has every required exact node");
        return result;
    }

    private static void assertInitialResourceDemandPreflight(
            DocumentProcessor owner,
            ProbeProcessor probe,
            ClosureInvocationInput input,
            Class<? extends ClosureResourceDemand> expectedDemandType,
            String expectedSuppliedBlueId,
            List<String> expectedExactBlueIds) {
        Map<DocumentId, Object> beforeDocuments = snapshotDocuments(input);
        Captures captures = new Captures();
        ClosureAttemptResult fullFirst;
        ClosureAttemptResult fullRetry;
        owner.administration().clearCaches();
        assertEquals(0, owner.administration().cacheEntryCount(),
                "The first typed-demand attempt must start cold");
        try (BlueClosureContracts contracts =
                     new BlueClosureContracts(owner, captures)) {
            fullFirst = contracts.admitClosureWithLifecycleQueue(input);
            int warmedCacheEntries = owner.administration()
                    .cacheEntryCount();
            assertTrue(warmedCacheEntries > 0,
                    "Typed-demand discovery must populate processor caches");
            fullRetry = contracts.admitClosureWithLifecycleQueue(input);
            assertEquals(warmedCacheEntries,
                    owner.administration().cacheEntryCount(),
                    "Full-lifecycle typed-demand retries must be cache invariant");
        }

        List<ClosureAttemptResult> attempts = Arrays.asList(
                fullFirst, fullRetry);
        for (ClosureAttemptResult attempt : attempts) {
            assertEquals(ClosureAttemptResult.Kind.NEEDS_RESOURCES,
                    attempt.kind());
            assertFalse(attempt.isComplete());
            assertNull(attempt.processResult());
            assertNull(attempt.totalGas());
            assertEquals(expectedExactBlueIds,
                    attempt.requiredExactBlueIds());
            assertEquals(1, attempt.resourceDemands().size());
            ClosureResourceDemand demand =
                    attempt.resourceDemands().get(0);
            assertTrue(expectedDemandType.isInstance(demand));
            assertEquals(A, demand.sourceDocumentId());
            assertEquals("/peer", demand.sourcePath());
            assertEquals(expectedSuppliedBlueId,
                    demand.suppliedValueBlueId());
        }
        assertEquals(fullFirst.resourceDemands(),
                fullRetry.resourceDemands());
        assertEquals(0, probe.executionCount,
                "Admission resource preflight must precede application code");
        assertTrue(captures.values.isEmpty(),
                "A resource demand must not publish completion evidence");
        assertEquals(beforeDocuments, snapshotDocuments(input),
                "Admission resource preflight must not mutate input documents");
        assertTrue(input.snapshot().occurrences().isEmpty());
    }

    private static Map<DocumentId, Object> snapshotDocuments(
            ClosureInvocationInput input) {
        LinkedHashMap<DocumentId, Object> result =
                new LinkedHashMap<DocumentId, Object>();
        for (ManagedDocumentSnapshot document
                : input.snapshot().managedDocuments()) {
            result.put(
                    document.documentId(),
                    NodeWireForm.get(
                            document.document(),
                            NodeWireForm.Strategy.SIMPLE));
        }
        return result;
    }

    private static AffectedClosureSnapshot finalizedSnapshot(
            Map<DocumentId, Node> sourceBodies,
            List<ManagedOccurrenceBinding> sourceBindings,
            List<DocumentId> publicRoots) {
        ArrayList<DocumentId> sourceOrderIds = new ArrayList<DocumentId>(
                sourceBodies.keySet());
        ArrayList<DocumentId> canonicalIds =
                new ArrayList<DocumentId>(sourceOrderIds);
        Collections.sort(canonicalIds);
        ArrayList<ManagedOccurrenceBinding> bindings =
                new ArrayList<ManagedOccurrenceBinding>(sourceBindings);
        Collections.sort(bindings);
        LinkedHashMap<DocumentId, Long> generations =
                new LinkedHashMap<DocumentId, Long>();
        LinkedHashMap<DocumentId, Node> bodies =
                new LinkedHashMap<DocumentId, Node>();
        for (DocumentId id : sourceOrderIds) {
            generations.put(id, Long.valueOf(1L));
            bodies.put(id, sourceBodies.get(id).clone());
        }
        ComponentFinalizationResult finalized =
                new ComponentFinalizationKernel().finalizeComponents(
                        new ComponentFinalizationInput(
                                ManagedDocumentGraph.fromBindings(
                                        sourceOrderIds, bindings),
                                generations,
                                bodies,
                                bindings));
        Set<DocumentId> publicSet =
                new LinkedHashSet<DocumentId>(publicRoots);
        ArrayList<ManagedDocumentSnapshot> documents =
                new ArrayList<ManagedDocumentSnapshot>();
        for (DocumentId id : canonicalIds) {
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
        for (FinalizedComponentEvidence component
                : finalized.components()) {
            components.add(component.component());
        }
        return ClosureEvidenceFactory.affectedClosure(
                1L,
                documents,
                finalized.finalizedGraph().bindings(),
                components,
                publicRoots);
    }

    private static AffectedClosureSnapshot initializedSnapshot(
            AffectedClosureSnapshot source,
            long parentEpoch,
            long childEpoch) {
        return initializedSnapshot(
                source, parentEpoch, childEpoch, null);
    }

    private static AffectedClosureSnapshot initializedSnapshot(
            AffectedClosureSnapshot source,
            long parentEpoch,
            long childEpoch,
            DocumentId terminatedDocumentId) {
        ArrayList<ManagedDocumentSnapshot> documents =
                new ArrayList<ManagedDocumentSnapshot>();
        for (ManagedDocumentSnapshot document
                : source.managedDocuments()) {
            documents.add(new ManagedDocumentSnapshot(
                    document.documentId(),
                    document.blueId(),
                    document.document(),
                    true,
                    document.terminated()
                            || document.documentId().equals(
                                    terminatedDocumentId),
                    document.publicRoot(),
                    A.equals(document.documentId())
                            ? parentEpoch : childEpoch,
                    document.componentGeneration()));
        }
        return ClosureEvidenceFactory.affectedClosure(
                source.graphGeneration(),
                documents,
                source.occurrences(),
                source.components(),
                source.publicRootDocumentIds());
    }

    private static ManagedComponentRebindScenario
    managedComponentRebindScenario(
            DocumentProcessor owner,
            Map<String, Node> exactNodes,
            boolean matchingSourceRepresentation) {
        ClosureEnvironment environment = environment(owner);

        Node authoredSource = new Node()
                .name("Same-epoch retained source")
                .properties("revision", new Node().value(BigInteger.ZERO))
                .contracts(new Node()
                        .properties("embedded", processEmbedded("/parent")));
        String authoredSourceBlueId = blueId(authoredSource);
        Node sourceBefore = authoredSource.clone();
        installInitializedMarker(sourceBefore, authoredSourceBlueId);
        String sourceBeforeBlueId = blueId(sourceBefore);

        Node authoredParent = new Node()
                .name("Same-epoch retained consumer")
                .properties(
                        "child", new Node().blueId(sourceBeforeBlueId))
                .properties("state", new Node().value("before"))
                .properties("observed", new Node().value(Boolean.FALSE))
                .contracts(new Node()
                        .properties("embedded", processEmbedded("/child"))
                        .properties("fromChild", embeddedChannel("/child"))
                        .properties("catchUpReact", handler("fromChild")));
        String authoredParentBlueId = blueId(authoredParent);
        Node parentBefore = authoredParent.clone();
        installInitializedMarker(parentBefore, authoredParentBlueId);
        String parentBeforeBlueId = blueId(parentBefore);
        Node currentParent = parentBefore.clone();
        NodePathEditor.put(
                currentParent,
                "/state",
                new Node().value("current"));

        Node receiptAfter = sourceBefore.clone();
        NodePathEditor.put(
                receiptAfter,
                "/revision",
                new Node().value(BigInteger.ONE));
        NodePathEditor.put(
                receiptAfter,
                "/parent",
                new Node().blueId(parentBeforeBlueId));
        String receiptAfterBlueId = blueId(receiptAfter);
        Node currentSource = receiptAfter.clone();
        if (!matchingSourceRepresentation) {
            NodePathEditor.put(
                    currentSource,
                    "/revision",
                    new Node().value(BigInteger.valueOf(2L)));
        }

        ManagedOccurrenceBinding historical =
                ManagedOccurrenceBinding.derived(
                        environment.managedBindingPolicyIdentity(),
                        A,
                        ScopeAddress.embedded("/child", 1L),
                        B,
                        sourceBeforeBlueId,
                        false,
                        Long.valueOf(0L));
        ManagedOccurrenceBinding reverse =
                ManagedOccurrenceBinding.derived(
                        environment.managedBindingPolicyIdentity(),
                        B,
                        ScopeAddress.embedded("/parent", 1L),
                        A,
                        parentBeforeBlueId,
                        true,
                        null);
        AffectedClosureSnapshot snapshot = initializedSnapshot(
                finalizedSnapshot(
                        bodies(A, currentParent, B, currentSource),
                        Arrays.asList(historical, reverse),
                        Collections.singletonList(A)),
                4L,
                1L);

        String sourceInvocationIdentity = hash('4');
        String eventBlueId = blueId(EVENT_CHILD);
        ManagedRootEventOccurrence sourceEvent =
                new ManagedRootEventOccurrence(
                        0L,
                        0L,
                        B,
                        ClosureIdentityService.INSTANCE
                                .eventOccurrenceIdentity(
                                        sourceInvocationIdentity,
                                        0L,
                                        eventBlueId),
                        exactEvent(EVENT_CHILD, eventBlueId),
                        false);
        ManagedDocumentTransitionReceipt sourceReceipt =
                ManagedDocumentTransitionReceipt.identified(
                        sourceInvocationIdentity,
                        0L,
                        B,
                        hash('5'),
                        sourceBeforeBlueId,
                        receiptAfterBlueId,
                        Collections.singletonList(sourceEvent),
                        5L);
        ManagedRevisionCause cause = ClosureEvidenceFactory
                .managedRevisionCause(
                        historical.occurrenceIdentity(),
                        0L,
                        1L,
                        receiptAfter,
                        sourceReceipt);
        ClosureInvocationInput input = ClosureEvidenceFactory
                .processClosure(
                        snapshot,
                        cause,
                        Collections.<DirectLogicalDelivery>emptyList(),
                        ClosureEvidenceFactory.executionPolicy(
                                GENEROUS_GAS,
                                Collections.<DocumentId, Long>emptyMap(),
                                "managed-same-epoch-component-rebind-v1"),
                        environment);

        exactNodes.put(sourceBeforeBlueId, sourceBefore.clone());
        exactNodes.put(receiptAfterBlueId, receiptAfter.clone());
        exactNodes.put(parentBeforeBlueId, parentBefore.clone());
        exactNodes.put(eventBlueId, EVENT_CHILD.clone());
        for (ManagedDocumentSnapshot document
                : snapshot.managedDocuments()) {
            exactNodes.put(document.blueId(), document.document());
        }
        return new ManagedComponentRebindScenario(
                input, receiptAfterBlueId);
    }

    private static ClosureInvocationInput
    managedCyclicSideActivationScenario(
            DocumentProcessor owner,
            Map<String, Node> exactNodes) {
        ClosureEnvironment environment = environment(owner);

        Node authoredSource = new Node()
                .name("Retained side-cycle source")
                .properties("revision", new Node().value(BigInteger.ZERO));
        String authoredSourceBlueId = blueId(authoredSource);
        Node sourceBefore = authoredSource.clone();
        installInitializedMarker(sourceBefore, authoredSourceBlueId);
        String sourceBeforeBlueId = blueId(sourceBefore);
        Node sourceAfter = sourceBefore.clone();
        NodePathEditor.put(
                sourceAfter,
                "/revision",
                new Node().value(BigInteger.ONE));
        String sourceAfterBlueId = blueId(sourceAfter);

        Node parentPlaceholder = new Node()
                .name("Managed activation cycle parent")
                .properties("peer", new Node().blueId("this#1"))
                .properties(
                        "source", new Node().blueId(sourceBeforeBlueId))
                .contracts(new Node().properties(
                        "embedded", processEmbedded("/peer", "/source")));
        Node peerPlaceholder = new Node()
                .name("Managed activation cycle peer")
                .properties("parent", new Node().blueId("this#0"))
                .contracts(new Node().properties(
                        "embedded", processEmbedded("/parent")));
        CyclicSetFinalization authoredCycle =
                new CircularSetIdentityCalculator().finalizeCyclicSet(
                        Arrays.asList(parentPlaceholder, peerPlaceholder));
        List<String> authoredCycleIds = canonicalBlueIds(authoredCycle);
        Node authoredParent = authoredCycle.membersInInputOrder().get(0)
                .canonicalMemberBody();
        Node authoredPeer = authoredCycle.membersInInputOrder().get(1)
                .canonicalMemberBody();
        materializeThis(authoredParent, authoredCycleIds);
        materializeThis(authoredPeer, authoredCycleIds);
        String authoredParentBlueId = authoredCycle
                .membersInInputOrder().get(0).finalBlueId();
        String authoredPeerBlueId = authoredCycle
                .membersInInputOrder().get(1).finalBlueId();
        Node parent = authoredParent.clone();
        Node peer = authoredPeer.clone();
        installInitializedMarker(parent, authoredParentBlueId);
        installInitializedMarker(peer, authoredPeerBlueId);

        ManagedOccurrenceBinding peerBinding =
                ManagedOccurrenceBinding.derived(
                        environment.managedBindingPolicyIdentity(),
                        A,
                        ScopeAddress.embedded("/peer", 1L),
                        B,
                        NodePathEditor.getOrNull(
                                parent, "/peer").getBlueId(),
                        true,
                        null);
        ManagedOccurrenceBinding parentBinding =
                ManagedOccurrenceBinding.derived(
                        environment.managedBindingPolicyIdentity(),
                        B,
                        ScopeAddress.embedded("/parent", 1L),
                        A,
                        NodePathEditor.getOrNull(
                                peer, "/parent").getBlueId(),
                        true,
                        null);
        ManagedOccurrenceBinding historicalSource =
                ManagedOccurrenceBinding.derived(
                        environment.managedBindingPolicyIdentity(),
                        A,
                        ScopeAddress.embedded("/source", 1L),
                        C,
                        sourceBeforeBlueId,
                        false,
                        Long.valueOf(0L));
        AffectedClosureSnapshot snapshot = initializedSnapshot(
                finalizedSnapshot(
                        bodies(
                                A, parent,
                                B, peer,
                                C, sourceAfter),
                        Arrays.asList(
                                peerBinding,
                                parentBinding,
                                historicalSource),
                        Collections.singletonList(A)),
                4L,
                1L);

        ManagedDocumentTransitionReceipt sourceReceipt =
                ManagedDocumentTransitionReceipt.identified(
                        hash('e'),
                        0L,
                        C,
                        hash('f'),
                        sourceBeforeBlueId,
                        sourceAfterBlueId,
                        Collections.<ManagedRootEventOccurrence>emptyList(),
                        7L);
        ManagedRevisionCause cause = ClosureEvidenceFactory
                .managedRevisionCause(
                        historicalSource.occurrenceIdentity(),
                        0L,
                        1L,
                        snapshot.managedDocument(C).document(),
                        sourceReceipt);
        ClosureInvocationInput input = ClosureEvidenceFactory
                .processClosure(
                        snapshot,
                        cause,
                        Collections.<DirectLogicalDelivery>emptyList(),
                        ClosureEvidenceFactory.executionPolicy(
                                GENEROUS_GAS,
                                Collections.<DocumentId, Long>emptyMap(),
                                "managed-cyclic-side-activation-v1"),
                        environment);

        exactNodes.put(authoredSourceBlueId, authoredSource.clone());
        exactNodes.put(sourceBeforeBlueId, sourceBefore.clone());
        exactNodes.put(sourceAfterBlueId, sourceAfter.clone());
        exactNodes.put(authoredParentBlueId, authoredParent.clone());
        exactNodes.put(authoredPeerBlueId, authoredPeer.clone());
        // The input A/B members are intentionally closure-owned only.  If
        // activation restores either pre-finalization side reference, exact
        // resource preflight must expose that stale replay as a demand.
        return input;
    }

    private static CyclicFixture twoMemberCycle(
            ClosureEnvironment environment,
            Node placeholderA,
            Node placeholderB,
            boolean publicA,
            boolean inlineAtoB) {
        CyclicSetFinalization language =
                new CircularSetIdentityCalculator().finalizeCyclicSet(
                        Arrays.asList(placeholderA, placeholderB));
        List<String> canonicalIds = canonicalBlueIds(language);
        Node bodyA = language.membersInInputOrder().get(0)
                .canonicalMemberBody();
        Node bodyB = language.membersInInputOrder().get(1)
                .canonicalMemberBody();
        materializeThis(bodyA, canonicalIds);
        materializeThis(bodyB, canonicalIds);
        String blueA = language.membersInInputOrder().get(0).finalBlueId();
        String blueB = language.membersInInputOrder().get(1).finalBlueId();
        List<ManagedOccurrenceBinding> bindings = Arrays.asList(
                ManagedOccurrenceBinding.derived(
                        environment.managedBindingPolicyIdentity(),
                        A,
                        ScopeAddress.embedded("/b", 1L),
                        B,
                        blueB,
                        true,
                        null),
                ManagedOccurrenceBinding.derived(
                        environment.managedBindingPolicyIdentity(),
                        B,
                        ScopeAddress.embedded("/a", 1L),
                        A,
                        blueA,
                        true,
                        null));
        AffectedClosureSnapshot reference = finalizedSnapshot(
                bodies(A, bodyA, B, bodyB),
                bindings,
                publicA
                        ? Collections.singletonList(A)
                        : Collections.<DocumentId>emptyList());
        AffectedClosureSnapshot snapshot = reference;
        if (inlineAtoB) {
            ArrayList<ManagedDocumentSnapshot> documents =
                    new ArrayList<ManagedDocumentSnapshot>();
            Node exactB = reference.managedDocument(B).document();
            for (ManagedDocumentSnapshot document
                    : reference.managedDocuments()) {
                Node representation = document.document();
                if (A.equals(document.documentId())) {
                    NodePathEditor.put(
                            representation, "/b", exactB.clone());
                }
                documents.add(new ManagedDocumentSnapshot(
                        document.documentId(),
                        document.blueId(),
                        representation,
                        document.initialized(),
                        document.terminated(),
                        document.publicRoot(),
                        document.epoch(),
                        document.componentGeneration()));
            }
            snapshot = ClosureEvidenceFactory.affectedClosure(
                    reference.graphGeneration(),
                    documents,
                    reference.occurrences(),
                    reference.components(),
                    reference.publicRootDocumentIds());
        }
        return new CyclicFixture(snapshot);
    }

    private static CyclicFixture selfCycle(
            ClosureEnvironment environment,
            Node placeholder,
            boolean publicRoot) {
        CyclicSetFinalization language =
                new CircularSetIdentityCalculator().finalizeCyclicSet(
                        Collections.singletonList(placeholder));
        Node body = language.membersInInputOrder().get(0)
                .canonicalMemberBody();
        materializeThis(body, canonicalBlueIds(language));
        String blue = language.membersInInputOrder().get(0).finalBlueId();
        ManagedOccurrenceBinding binding =
                ManagedOccurrenceBinding.derived(
                        environment.managedBindingPolicyIdentity(),
                        A,
                        ScopeAddress.embedded("/self", 1L),
                        A,
                        blue,
                        true,
                        null);
        AffectedClosureSnapshot snapshot = finalizedSnapshot(
                Collections.singletonMap(A, body),
                Collections.singletonList(binding),
                publicRoot
                        ? Collections.singletonList(A)
                        : Collections.<DocumentId>emptyList());
        return new CyclicFixture(snapshot);
    }

    private static List<String> canonicalBlueIds(
            CyclicSetFinalization finalization) {
        ArrayList<String> result = new ArrayList<String>();
        for (CyclicMemberFinalization member
                : finalization.membersInCanonicalOrder()) {
            result.add(member.finalBlueId());
        }
        return Collections.unmodifiableList(result);
    }

    private static void materializeThis(
            Node node,
            List<String> canonicalBlueIds) {
        if (node == null) {
            return;
        }
        String selected = node.getBlueId();
        if (selected != null && selected.startsWith("this#")) {
            node.blueId(canonicalBlueIds.get(
                    Integer.parseInt(selected.substring(5))));
        }
        materializeThis(node.getType(), canonicalBlueIds);
        materializeThis(node.getItemType(), canonicalBlueIds);
        materializeThis(node.getKeyType(), canonicalBlueIds);
        materializeThis(node.getValueType(), canonicalBlueIds);
        materializeThis(node.getBlue(), canonicalBlueIds);
        materializeThis(node.getContracts(), canonicalBlueIds);
        if (node.getItems() != null) {
            for (Node item : node.getItems()) {
                materializeThis(item, canonicalBlueIds);
            }
        }
        if (node.getProperties() != null) {
            for (Node child : node.getProperties().values()) {
                materializeThis(child, canonicalBlueIds);
            }
        }
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

    private static LinkedHashMap<DocumentId, Node> bodies(
            DocumentId firstId,
            Node first,
            DocumentId secondId,
            Node second,
            DocumentId thirdId,
            Node third) {
        LinkedHashMap<DocumentId, Node> result = bodies(
                firstId, first, secondId, second);
        result.put(thirdId, third);
        return result;
    }

    private static Node recordingDocument(String label) {
        return new Node()
                .name("Canonical initialization " + label)
                .properties("label", new Node().value(label))
                .contracts(new Node()
                        .properties("lifecycle", lifecycleChannel())
                        .properties("recordInit", handler("lifecycle")));
    }

    private static Node terminatingDocument(String name) {
        return new Node()
                .name(name)
                .contracts(new Node()
                        .properties("lifecycle", lifecycleChannel())
                        .properties(
                                "terminateOnInit",
                                handler(
                                        "lifecycle",
                                        initiatedPattern())));
    }

    private static Node terminatingSelfCycle() {
        return new Node()
                .name("Terminating cyclic Root")
                .properties("self", new Node().blueId("this#0"))
                .contracts(new Node()
                        .properties("embedded", processEmbedded("/self"))
                        .properties("lifecycle", lifecycleChannel())
                        .properties(
                                "terminateOnInit",
                                handler(
                                        "lifecycle",
                                        initiatedPattern())));
    }

    private static Node finiteCycleA() {
        return new Node()
                .name("Finite cyclic A")
                .properties("b", new Node().blueId("this#1"))
                .properties("finished", new Node().value(Boolean.FALSE))
                .contracts(new Node()
                        .properties("embedded", processEmbedded("/b"))
                        .properties("lifecycle", lifecycleChannel())
                        .properties("fromB", embeddedChannel("/b"))
                        .properties("emitPing", handler("lifecycle"))
                        .properties("finish", handler("fromB")));
    }

    private static Node finiteCycleB() {
        return new Node()
                .name("Finite cyclic B")
                .properties("a", new Node().blueId("this#0"))
                .contracts(new Node()
                        .properties("embedded", processEmbedded("/a"))
                        .properties("fromA", embeddedChannel("/a"))
                        .properties("emitPong", handler("fromA")));
    }

    private static Node loopingCycleA() {
        return new Node()
                .name("Looping cyclic A")
                .properties("b", new Node().blueId("this#1"))
                .contracts(new Node()
                        .properties("embedded", processEmbedded("/b"))
                        .properties("lifecycle", lifecycleChannel())
                        .properties("fromB", embeddedChannel("/b"))
                        .properties("seedLoop", handler("lifecycle"))
                        .properties("relayLoopA", handler("fromB")));
    }

    private static Node loopingCycleB() {
        return new Node()
                .name("Looping cyclic B")
                .properties("a", new Node().blueId("this#0"))
                .contracts(new Node()
                        .properties("embedded", processEmbedded("/a"))
                        .properties("fromA", embeddedChannel("/a"))
                        .properties("relayLoopB", handler("fromA")));
    }

    private static Node quietCycleA() {
        return new Node()
                .name("Quiet cyclic A")
                .properties("b", new Node().blueId("this#1"))
                .contracts(new Node()
                        .properties("embedded", processEmbedded("/b")));
    }

    private static Node quietCycleB() {
        return new Node()
                .name("Quiet cyclic B")
                .properties("a", new Node().blueId("this#0"))
                .contracts(new Node()
                        .properties("embedded", processEmbedded("/a")));
    }

    private static Node exactQuietCycleA() {
        return quietCycleA().type(
                new Node().blueId(EXACT_DOCUMENT_TYPE_BLUE_ID));
    }

    private static Node exactQuietCycleB() {
        return quietCycleB().type(
                new Node().blueId(EXACT_DOCUMENT_TYPE_BLUE_ID));
    }

    private static Node lifecycleChannel() {
        return typed(RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL);
    }

    private static Node triggeredChannel(String eventBlueId) {
        return typed(RuntimeBlueIds.TRIGGERED_EVENT_CHANNEL)
                .properties("event", new Node().blueId(eventBlueId));
    }

    private static Node embeddedChannel(String sourcePath) {
        return typed(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL)
                .properties("sourcePath", new Node().value(sourcePath));
    }

    private static Node updateChannel(String path) {
        return typed(RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL)
                .properties("path", new Node().value(path));
    }

    private static Node processEmbedded(String... paths) {
        ArrayList<Node> items = new ArrayList<Node>();
        for (String path : paths) {
            items.add(new Node().value(path));
        }
        return typed(RuntimeBlueIds.PROCESS_EMBEDDED)
                .properties("paths", new Node().items(items));
    }

    private static Node handler(String channel) {
        return typed(HANDLER_BLUE_ID)
                .properties("channel", new Node().value(channel));
    }

    private static Node handler(String channel, Node eventPattern) {
        return handler(channel)
                .properties("event", eventPattern);
    }

    private static Node initiatedPattern() {
        return typed(RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED);
    }

    private static Node terminatedPattern() {
        return typed(RuntimeBlueIds.DOCUMENT_PROCESSING_TERMINATED);
    }

    private static Node typed(String blueId) {
        return new Node().type(new Node().blueId(blueId));
    }

    private static Node event(String kind) {
        return new Node().properties(
                "kind", new Node().value(kind));
    }

    private static String blueId(Node value) {
        return DirectBlueIdCalculator.calculateBlueId(value);
    }

    private static ExactEventIdentityEvidence exactEvent(
            Node event,
            String eventBlueId) {
        return ExactEventIdentityEvidence.verify(
                null, event, eventBlueId, null);
    }

    private static void installInitializedMarker(
            Node body,
            String beforeBlueId) {
        Node contracts = body.getContracts();
        if (contracts == null) {
            contracts = new Node();
            body.contracts(contracts);
        }
        contracts.properties(
                ProcessorContractConstants.KEY_INITIALIZED,
                typed(RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER)
                        .properties(
                                ProcessorContractConstants.KEY_DOCUMENT,
                                new Node().blueId(beforeBlueId)));
    }

    private static void installTerminatedMarker(
            Node body,
            String cause,
            String reason) {
        Node contracts = body.getContracts();
        if (contracts == null) {
            contracts = new Node();
            body.contracts(contracts);
        }
        contracts.properties(
                ProcessorContractConstants.KEY_TERMINATED,
                typed(RuntimeBlueIds.PROCESSING_TERMINATED_MARKER)
                        .properties(
                                ProcessorContractConstants.KEY_CAUSE,
                                new Node().value(cause))
                        .properties(
                                ProcessorContractConstants.KEY_REASON,
                                new Node().value(reason)));
    }

    private static boolean initiated(ProcessorExecutionContext context) {
        Node type = context.event().getType();
        return type != null
                && RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED.equals(
                        type.getBlueId());
    }

    private static String occurrenceKind(
            ProcessorExecutionContext context) {
        return context.occurrenceEvent().getAsText("/kind");
    }

    private static void assertSuccess(ClosureProcessResult result) {
        assertEquals(ProcessorStatus.SUCCESS,
                result.status(), diagnostic(result));
        assertTrue(result.commits());
        assertNotNull(result.commitCompanion());
        assertNull(result.diagnostic());
    }

    private static void assertExactParity(
            ClosureProcessResult left,
            ClosureProcessResult right) {
        assertEquals(left.status(), right.status());
        assertEquals(left.invocationIdentity(), right.invocationIdentity());
        assertEquals(left.inputClosureIdentity(),
                right.inputClosureIdentity());
        assertEquals(left.outputClosureIdentity(),
                right.outputClosureIdentity());
        assertEquals(left.graphGeneration(), right.graphGeneration());
        assertEquals(left.occurrenceBindingSetIdentity(),
                right.occurrenceBindingSetIdentity());
        assertEquals(left.graphChangesIdentity(),
                right.graphChangesIdentity());
        assertEquals(left.subscriptionDeltasIdentity(),
                right.subscriptionDeltasIdentity());
        assertEquals(left.checkpointWritesIdentity(),
                right.checkpointWritesIdentity());
        assertEquals(left.publicEventsIdentity(),
                right.publicEventsIdentity());
        assertEquals(left.totalGas(), right.totalGas());
        assertEquals(left.gasTraceIdentity(),
                right.gasTraceIdentity());
        assertEquals(left.managedTransitionReceiptsIdentity(),
                right.managedTransitionReceiptsIdentity());
        assertEquals(resultingDocumentProjection(left),
                resultingDocumentProjection(right));
    }

    private static List<String> resultingDocumentProjection(
            ClosureProcessResult result) {
        ArrayList<String> values = new ArrayList<String>();
        for (ResultingDocument document : result.resultingDocuments()) {
            values.add(document.documentId().value()
                    + "|" + document.beforeBlueId()
                    + "|" + document.afterBlueId()
                    + "|" + document.initialized()
                    + "|" + document.terminated()
                    + "|" + document.epoch()
                    + "|" + NodeWireForm.get(
                            document.document(),
                            NodeWireForm.Strategy.SIMPLE));
        }
        return Collections.unmodifiableList(values);
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
            assertNodeEquals(before.document(), after.document());
        }
        assertTrue(result.graphChanges().isEmpty());
        assertTrue(result.subscriptionDeltas().isEmpty());
        assertTrue(result.checkpointWrites().isEmpty());
        assertTrue(result.publicEvents().isEmpty());
        assertTrue(result.managedTransitionReceipts().isEmpty());
        assertNull(result.commitCompanion());
    }

    private static ResultingDocument document(
            ClosureProcessResult result,
            DocumentId documentId) {
        for (ResultingDocument document : result.resultingDocuments()) {
            if (documentId.equals(document.documentId())) {
                return document;
            }
        }
        throw new AssertionError(
                "Missing resulting document " + documentId.value());
    }

    private static ManagedDocumentTransitionReceipt receipt(
            ClosureProcessResult result,
            DocumentId documentId) {
        for (ManagedDocumentTransitionReceipt receipt
                : result.managedTransitionReceipts()) {
            if (documentId.equals(receipt.documentId())) {
                return receipt;
            }
        }
        throw new AssertionError(
                "Missing managed-transition receipt "
                        + documentId.value());
    }

    private static long managedTransitionGas(
            ClosureProcessResult result) {
        long total = 0L;
        for (ManagedDocumentTransitionReceipt receipt
                : result.managedTransitionReceipts()) {
            total = Math.addExact(total, receipt.admittedGas());
        }
        return total;
    }

    private static List<String> eventBlueIds(
            List<PublicEventOccurrence> events) {
        ArrayList<String> result = new ArrayList<String>();
        for (PublicEventOccurrence event : events) {
            result.add(event.eventBlueId());
        }
        return Collections.unmodifiableList(result);
    }

    private static String duplicateEventIdentityProjection(
            ClosureInvocationInput input,
            PublicEventOccurrence first,
            PublicEventOccurrence second) {
        return input.invocationIdentity()
                + "|" + EVENT_ONE_BLUE_ID
                + "|" + first.eventOccurrenceIdentity()
                + "|" + second.eventOccurrenceIdentity();
    }

    private static String gasFailureProjection(
            ClosureProcessResult result,
            ClosureImplementationEvidence evidence) {
        StringBuilder projection = new StringBuilder();
        projection.append(result.invocationIdentity())
                .append('|').append(result.totalGas())
                .append('|').append(result.gasTrace().size())
                .append('|').append(result.gasTraceIdentity())
                .append('|').append(result.diagnostic().category())
                .append('|').append(result.diagnostic().message())
                .append('|').append(result.diagnostic().details());
        int prefixSize = Math.min(8, result.gasTrace().size());
        for (int index = 0; index < prefixSize; index++) {
            GasTraceEntry entry = result.gasTrace().get(index);
            projection.append("|g").append(index)
                    .append(':').append(entry.sequence())
                    .append(':').append(entry.namespace())
                    .append(':').append(entry.counter())
                    .append(':').append(entry.quantity())
                    .append(':').append(entry.weight())
                    .append(':').append(entry.subtotal())
                    .append(':').append(entry.documentId())
                    .append(':').append(entry.scopePath())
                    .append(':').append(entry.workOccurrenceId())
                    .append(':').append(entry.reason());
        }
        projection.append("|rejected:")
                .append(rejectedChargeProjection(
                        result.rejectedCharge()));
        List<String> works = workIdentities(evidence);
        projection.append("|works:").append(works.size());
        if (!works.isEmpty()) {
            projection.append(':').append(works.get(0))
                    .append(':').append(works.get(
                            works.size() - 1));
        }
        return projection.toString();
    }

    private static List<WorkKind> workKinds(
            ClosureImplementationEvidence evidence) {
        assertNotNull(evidence);
        ArrayList<WorkKind> result = new ArrayList<WorkKind>();
        for (ClosureWorkOccurrence work : evidence.workTrace()) {
            result.add(work.kind());
        }
        return Collections.unmodifiableList(result);
    }

    private static List<String> workIdentities(
            ClosureImplementationEvidence evidence) {
        assertNotNull(evidence);
        ArrayList<String> result = new ArrayList<String>();
        for (ClosureWorkOccurrence work : evidence.workTrace()) {
            result.add(work.ordinal()
                    + "|" + work.kind().name()
                    + "|" + work.targetDocumentId().value()
                    + "|" + work.workIdentity());
        }
        return Collections.unmodifiableList(result);
    }

    private static long countKind(
            ClosureImplementationEvidence evidence,
            WorkKind kind) {
        long result = 0L;
        for (ClosureWorkOccurrence work : evidence.workTrace()) {
            if (kind == work.kind()) {
                result++;
            }
        }
        return result;
    }

    private static ClosureWorkOccurrence onlyWork(
            ClosureImplementationEvidence evidence,
            WorkKind kind) {
        ClosureWorkOccurrence result = null;
        for (ClosureWorkOccurrence work : evidence.workTrace()) {
            if (kind != work.kind()) {
                continue;
            }
            assertNull(result, "Expected exactly one " + kind + " work");
            result = work;
        }
        assertNotNull(result, "Missing " + kind + " work");
        return result;
    }

    private static GasTraceEntry onlyCharge(
            ClosureProcessResult result,
            String counter) {
        GasTraceEntry selected = null;
        for (GasTraceEntry entry : result.gasTrace()) {
            if (!counter.equals(entry.counter())) {
                continue;
            }
            assertNull(selected,
                    "Expected exactly one " + counter + " charge");
            selected = entry;
        }
        assertNotNull(selected, "Missing " + counter + " charge");
        return selected;
    }

    private static List<String> finalizationProjection(
            ClosureImplementationEvidence evidence) {
        ArrayList<String> result = new ArrayList<String>();
        for (TentativeFinalization finalization
                : evidence.tentativeFinalizations()) {
            result.add(finalization.ordinal()
                    + "|" + finalization.boundary().kind().name()
                    + "|" + finalization.boundary().afterWorkOrdinal()
                    + "|" + finalization.masterBlueId()
                    + "|" + finalization.memberBlueIds()
                    + "|" + finalization.canonicalBytes());
        }
        return Collections.unmodifiableList(result);
    }

    private static List<Object> rejectedChargeProjection(
            RejectedCharge charge) {
        assertNotNull(charge);
        return Arrays.<Object>asList(
                charge.rejectedChargeIdentity(),
                charge.namespace(),
                charge.counter(),
                Long.valueOf(charge.quantity()),
                Long.valueOf(charge.weight()),
                Long.valueOf(charge.subtotal()),
                charge.applicableCap().kind(),
                charge.applicableCap().documentId(),
                Long.valueOf(charge.remainingBeforeCharge()),
                charge.owner().kind(),
                charge.owner().workOccurrenceIdentity(),
                charge.owner().finalizationOrdinal(),
                charge.owner().componentIdentity(),
                charge.owner().componentGeneration());
    }

    private static void assertNodeEquals(Node expected, Node actual) {
        assertEquals(
                NodeWireForm.get(expected, NodeWireForm.Strategy.SIMPLE),
                NodeWireForm.get(actual, NodeWireForm.Strategy.SIMPLE));
    }

    private static String diagnostic(ClosureProcessResult result) {
        return result.diagnostic() == null
                ? null
                : result.diagnostic().category()
                        + ": " + result.diagnostic().message()
                        + " " + result.diagnostic().details();
    }

    private static String hash(char value) {
        StringBuilder result = new StringBuilder("sha256:");
        for (int index = 0; index < 64; index++) {
            result.append(value);
        }
        return result.toString();
    }

    /** External source model used solely to preserve exact source checkpoints. */
    public static final class RetainedSourceChannel extends ChannelContract {
    }

    private static final class RetainedSourceChannelProcessor
            implements ChannelProcessor<RetainedSourceChannel> {
        @Override
        public Class<RetainedSourceChannel> contractType() {
            return RetainedSourceChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<RetainedSourceChannel>
        externalSubscriptionFunctions() {
            return new ExternalChannelSubscriptionFunctions<RetainedSourceChannel>() {
                @Override
                public List<String> channelKeys(RetainedSourceChannel channel) {
                    return Collections.singletonList("retained-source");
                }

                @Override
                public boolean preselects(RetainedSourceChannel channel, Node event) {
                    return true;
                }

                @Override
                public boolean accepts(RetainedSourceChannel channel, Node event) {
                    return true;
                }

                @Override
                public Node payload(RetainedSourceChannel channel, Node event) {
                    return event.clone();
                }

                @Override
                public String checkpointDomainDiscriminator(RetainedSourceChannel channel) {
                    return "retained-nested-checkpoint-v1";
                }
            };
        }
    }

    /** Test-only Timeline-shaped source whose domain includes the raw-key catalog. */
    public static final class RetainedCatalogChannel extends ChannelContract {
    }

    private static final class RetainedCatalogChannelProcessor
            implements ChannelProcessor<RetainedCatalogChannel> {
        @Override
        public Class<RetainedCatalogChannel> contractType() {
            return RetainedCatalogChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<RetainedCatalogChannel>
        externalSubscriptionFunctions() {
            return new ExternalChannelSubscriptionFunctions<RetainedCatalogChannel>() {
                @Override
                public List<String> channelKeys(RetainedCatalogChannel channel) {
                    return Collections.singletonList("retained-catalog");
                }

                @Override
                public boolean preselects(RetainedCatalogChannel channel, Node event) {
                    return true;
                }

                @Override
                public boolean accepts(RetainedCatalogChannel channel, Node event) {
                    return true;
                }

                @Override
                public Node payload(RetainedCatalogChannel channel, Node event) {
                    return event.clone();
                }

                @Override
                public String checkpointDomainDiscriminator(RetainedCatalogChannel channel,
                        ExternalChannelFunctionContext context) {
                    context.dependOnSameScopeChannelCatalog();
                    return "retained-catalog-checkpoint-v1";
                }
            };
        }
    }

    /** Test-only Handler model; the contract key selects fixed behavior. */
    public static final class ProbeHandler extends HandlerContract {
    }

    private static final class ProbeProcessor
            implements HandlerProcessor<ProbeHandler> {

        private final List<String> initializationOrder =
                new ArrayList<String>();
        private final List<String> observedEventKinds =
                new ArrayList<String>();
        private String reactivationTargetBlueId;
        private String dormantActivationTargetBlueId;
        private int reactivationInitializationCount;
        private int dormantTargetInitializationCount;
        private int executionCount;

        @Override
        public Class<ProbeHandler> contractType() {
            return ProbeHandler.class;
        }

        @Override
        public void execute(
                ProbeHandler contract,
                ProcessorExecutionContext context) {
            executionCount++;
            String key = context.contractKey();
            if ("initPatch".equals(key)) {
                if (initiated(context)) {
                    context.applyPatch(JsonPatch.add(
                            "/state",
                            new Node().value("initialized")));
                }
            } else if ("emitOne".equals(key)) {
                if (initiated(context)) {
                    context.emitEvent(EVENT_ONE.clone());
                }
            } else if ("emitTwice".equals(key)) {
                if (initiated(context)) {
                    context.emitEvent(EVENT_ONE.clone());
                    context.emitEvent(EVENT_ONE.clone());
                }
            } else if ("reactOne".equals(key)) {
                context.applyPatch(JsonPatch.replace(
                        "/reaction",
                        new Node().value("one-reaction")));
            } else if ("countOne".equals(key)) {
                Node count = context.documentAt("/count");
                BigInteger value = (BigInteger) count.getValue();
                context.applyPatch(JsonPatch.replace(
                        "/count",
                        new Node().value(value.add(BigInteger.ONE))));
            } else if ("childEmit".equals(key)) {
                if (initiated(context)) {
                    context.emitEvent(EVENT_CHILD.clone());
                }
            } else if ("containReact".equals(key)) {
                observedEventKinds.add(occurrenceKind(context));
                context.applyPatch(JsonPatch.replace(
                        "/observed",
                        new Node().value(Boolean.TRUE)));
            } else if ("attachRetained".equals(key)) {
                context.applyPatch(JsonPatch.add("/child", context.event().getAsNode("/child")));
                context.applyPatch(JsonPatch.add("/contracts/embedded", processEmbedded("/child")));
                context.applyPatch(JsonPatch.replace("/observed", new Node().value(Boolean.TRUE)));
            } else if ("catchUpReact".equals(key)) {
                observedEventKinds.add(occurrenceKind(context));
                context.applyPatch(JsonPatch.replace(
                        "/observed",
                        new Node().value(Boolean.TRUE)));
                context.emitEvent(EVENT_PUBLIC.clone());
            } else if ("catchUpEmitOnly".equals(key)) {
                observedEventKinds.add(occurrenceKind(context));
                context.emitEvent(EVENT_PUBLIC.clone());
            } else if ("catchUpDetach".equals(key)) {
                observedEventKinds.add(occurrenceKind(context));
                if (context.documentAt("/child") != null) {
                    context.applyPatch(JsonPatch.remove("/child"));
                }
                BigInteger deliveries = (BigInteger) context.documentAt(
                        "/deliveries").getValue();
                context.applyPatch(JsonPatch.replace(
                        "/deliveries",
                        new Node().value(
                                deliveries.add(BigInteger.ONE))));
            } else if ("catchUpRetarget".equals(key)) {
                observedEventKinds.add(occurrenceKind(context));
                context.applyPatch(JsonPatch.replace(
                        "/child", context.documentAt("/candidate").clone()));
                BigInteger deliveries = (BigInteger) context.documentAt(
                        "/deliveries").getValue();
                context.applyPatch(JsonPatch.replace("/deliveries",
                        new Node().value(deliveries.add(BigInteger.ONE))));
            } else if ("publicEmit".equals(key)) {
                if (initiated(context)) {
                    context.emitEvent(EVENT_PUBLIC.clone());
                }
            } else if ("patchSource".equals(key)) {
                if (initiated(context)) {
                    context.applyPatch(JsonPatch.replace(
                            "/source", new Node().value("after")));
                }
            } else if ("handleUpdate".equals(key)) {
                context.applyPatch(JsonPatch.replace(
                        "/handled", new Node().value(Boolean.TRUE)));
            } else if ("installFutureUpdateSurface".equals(key)) {
                if (initiated(context)) {
                    Node contracts = context.documentAt(
                            "/contracts");
                    contracts.properties(
                            "createdUpdates",
                            updateChannel("/contracts"));
                    contracts.properties(
                            "createdUpdateHandler",
                            handler("createdUpdates"));
                    context.applyPatch(JsonPatch.replace(
                            "/contracts", contracts));
                }
            } else if ("createdUpdateHandler".equals(key)) {
                context.applyPatch(JsonPatch.replace(
                        "/createdRouteRan",
                        new Node().value(Boolean.TRUE)));
            } else if ("installThenUseFutureUpdateSurface".equals(key)) {
                if (initiated(context)) {
                    Node contracts = context.documentAt(
                            "/contracts");
                    contracts.properties(
                            "futureUpdates",
                            updateChannel("/laterSignal"));
                    contracts.properties(
                            "futureUpdateHandler",
                            handler("futureUpdates"));
                    context.applyPatch(JsonPatch.replace(
                            "/contracts", contracts));
                    context.applyPatch(JsonPatch.replace(
                            "/laterSignal",
                            new Node().value(Boolean.TRUE)));
                }
            } else if ("futureUpdateHandler".equals(key)) {
                BigInteger count = (BigInteger) context.documentAt(
                        "/futureRouteRuns").getValue();
                context.applyPatch(JsonPatch.replace(
                        "/futureRouteRuns",
                        new Node().value(count.add(BigInteger.ONE))));
            } else if ("removeCurrentUpdateSurface".equals(key)) {
                if (initiated(context)) {
                    Node first = context.documentAt(
                            "/contracts");
                    first.getProperties().remove("updates");
                    first.getProperties().remove(
                            "handleRemovedUpdate");
                    first.getProperties().remove(
                            "removeCurrentUpdateSurface");
                    context.applyPatch(JsonPatch.replace(
                            "/contracts", first));
                }
            } else if ("handleRemovedUpdate".equals(key)) {
                BigInteger count = (BigInteger) context.documentAt(
                        "/removedRouteRuns").getValue();
                context.applyPatch(JsonPatch.replace(
                        "/removedRouteRuns",
                        new Node().value(count.add(BigInteger.ONE))));
            } else if ("reactivateSelf".equals(key)) {
                if (initiated(context)) {
                    reactivationInitializationCount++;
                    context.applyPatch(JsonPatch.add(
                            "/self",
                            new Node().blueId(
                                    reactivationTargetBlueId)));
                }
            } else if ("activateDormant".equals(key)) {
                if (initiated(context)) {
                    context.applyPatch(JsonPatch.add(
                            "/reserved",
                            new Node().blueId(
                                    dormantActivationTargetBlueId)));
                    context.applyPatch(JsonPatch.add(
                            "/contracts/embedded",
                            processEmbedded("/reserved")));
                }
            } else if ("recordDormantInit".equals(key)) {
                if (initiated(context)) {
                    dormantTargetInitializationCount++;
                }
            } else if ("parentPatchThenFinish".equals(key)) {
                if (initiated(context)) {
                    context.applyPatch(JsonPatch.replace(
                            "/source", new Node().value("after")));
                    context.applyPatch(JsonPatch.replace(
                            "/parentFinished",
                            new Node().value(Boolean.TRUE)));
                }
            } else if ("nestedEmitOne".equals(key)) {
                context.emitEvent(EVENT_ONE.clone());
            } else if ("observeParentFinished".equals(key)) {
                context.applyPatch(JsonPatch.replace(
                        "/observedParentFinished",
                        new Node().value(context.documentAt(
                                "/parentFinished").getValue())));
            } else if ("nestedTerminate".equals(key)) {
                context.terminate(
                        "nested-update", "parent-suspended");
            } else if ("observeTerminationParentFinished".equals(key)) {
                context.applyPatch(JsonPatch.replace(
                        "/terminationObservedParentFinished",
                        new Node().value(context.documentAt(
                                "/parentFinished").getValue())));
            } else if ("terminateOnInit".equals(key)) {
                if (initiated(context)) {
                    context.terminate("completed", "initialization");
                }
            } else if ("recordInit".equals(key)) {
                if (initiated(context)) {
                    initializationOrder.add(
                            context.documentAt("/label").getValue()
                                    .toString());
                }
            } else if ("emitPing".equals(key)) {
                if (initiated(context)) {
                    context.emitEvent(EVENT_PING.clone());
                }
            } else if ("emitPong".equals(key)) {
                observedEventKinds.add(occurrenceKind(context));
                context.emitEvent(EVENT_PONG.clone());
            } else if ("finish".equals(key)) {
                observedEventKinds.add(occurrenceKind(context));
                context.applyPatch(JsonPatch.replace(
                        "/finished", new Node().value(Boolean.TRUE)));
            } else if ("seedLoop".equals(key)) {
                if (initiated(context)) {
                    context.emitEvent(EVENT_LOOP.clone());
                }
            } else if ("relayLoopA".equals(key)
                    || "relayLoopB".equals(key)) {
                assertEquals(EVENT_LOOP_BLUE_ID,
                        blueId(context.occurrenceEvent()));
                context.emitEvent(EVENT_LOOP.clone());
            } else if ("firstPatch".equals(key)) {
                if (initiated(context)) {
                    context.applyPatch(JsonPatch.replace(
                            "/first", new Node().value(Boolean.TRUE)));
                }
            } else if ("laterFail".equals(key)) {
                if (initiated(context)) {
                    throw new IllegalStateException(
                            "deterministic later-member failure");
                }
            } else if ("installUnknown".equals(key)) {
                if (initiated(context)) {
                    context.applyPatch(JsonPatch.add(
                            "/child",
                            new Node().name(
                                    "Unknown child created during initialization")
                                    .properties(
                                            "payload",
                                            new Node().value(Boolean.TRUE))));
                }
            }
        }
    }

    private static final class CyclicFixture {
        private final AffectedClosureSnapshot snapshot;

        private CyclicFixture(AffectedClosureSnapshot snapshot) {
            this.snapshot = snapshot;
        }
    }

    private static final class ManagedComponentRebindScenario {
        private final ClosureInvocationInput input;
        private final String receiptAfterBlueId;

        private ManagedComponentRebindScenario(
                ClosureInvocationInput input,
                String receiptAfterBlueId) {
            this.input = input;
            this.receiptAfterBlueId = receiptAfterBlueId;
        }
    }

    private static final class Capture
            implements ClosureExecutionObserver {
        private ClosureImplementationEvidence evidence;

        @Override
        public void onExecutionEvidence(
                ClosureImplementationEvidence value) {
            evidence = value;
        }
    }

    private static final class Captures
            implements ClosureExecutionObserver {
        private final List<ClosureImplementationEvidence> values =
                new ArrayList<ClosureImplementationEvidence>();

        @Override
        public void onExecutionEvidence(
                ClosureImplementationEvidence value) {
            values.add(value);
        }
    }

    private static final class CountingNodeProvider
            implements NodeProvider {
        private final NodeProvider runtime =
                BlueRuntimeTypeRegistry.getDefault().asProvider();
        private int exactFetches;

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            if (EXACT_DOCUMENT_TYPE_BLUE_ID.equals(blueId)) {
                exactFetches++;
                return Collections.singletonList(
                        EXACT_DOCUMENT_TYPE.clone());
            }
            return runtime.fetchByBlueId(blueId);
        }
    }

    private static final class TestNodeProvider implements NodeProvider {
        private final NodeProvider delegate;
        private final NodeProvider core =
                BlueCoreTypeRegistry.INSTANCE.verifiedProvider();
        private final NodeProvider runtime =
                BlueRuntimeTypeRegistry.getDefault().asProvider();

        private TestNodeProvider(NodeProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            if (EVENT_ONE_BLUE_ID.equals(blueId)) {
                return Collections.singletonList(EVENT_ONE.clone());
            }
            if (delegate != null) {
                List<Node> resolved = delegate.fetchByBlueId(blueId);
                if (resolved != null && !resolved.isEmpty()) {
                    return resolved;
                }
            }
            List<Node> coreResolved = core.fetchByBlueId(blueId);
            if (coreResolved != null && !coreResolved.isEmpty()) {
                return coreResolved;
            }
            return runtime.fetchByBlueId(blueId);
        }
    }
}
