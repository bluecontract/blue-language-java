package blue.language.processor.closure;

import blue.language.identity.CircularSetIdentityCalculator;
import blue.language.identity.CyclicMemberFinalization;
import blue.language.identity.CyclicSetFinalization;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.ClosureRuntimeDescriptor;
import blue.language.processor.ContractProcessorRegistry;
import blue.language.processor.ContractProcessorRegistryBuilder;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExternalChannelFunctionContext;
import blue.language.processor.ExternalChannelSubscriptionFunctions;
import blue.language.processor.GasSchedule;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused production proof for the bounded successful-admission lane. */
final class ClosureAdmissionExecutionTest {

    private static final ClosureIdentityService IDENTITIES =
            ClosureIdentityService.INSTANCE;
    private static final DocumentId A = new DocumentId("a");
    private static final DocumentId B = new DocumentId("b");
    private static final Node SUSPENDING_CHANNEL_TYPE =
            new Node().name("Admission resource channel");
    private static final String SUSPENDING_CHANNEL_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(
                    SUSPENDING_CHANNEL_TYPE);
    private static final Node SUSPENDING_HANDLER_TYPE =
            new Node().name("Admission resource handler");
    private static final String SUSPENDING_HANDLER_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(
                    SUSPENDING_HANDLER_TYPE);
    private static final String UNAVAILABLE_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(
                    new Node().name("Unavailable admission contract type"));

    @Test
    void initializesEveryCyclicMemberAsAnIndependentRootAndCommitsOneMarkerBatch() {
        try (DocumentProcessor owner = DocumentProcessor.builder().build()) {
            Capture capture = new Capture();
            ClosureAttemptResult attempt;
            try (BlueClosureContracts contracts =
                         new BlueClosureContracts(owner, capture)) {
                attempt = contracts.admitClosure(admission(
                        owner, 100000L, 128L));
            }

            assertTrue(attempt.isComplete());
            assertEquals(ProcessorStatus.SUCCESS,
                    attempt.processResult().status(), diagnostic(attempt));
            assertTrue(attempt.processResult().commits());
            assertNotNull(capture.evidence);
            assertTrue(capture.evidence.complete());
            assertEquals(Arrays.asList(A, B),
                    targets(capture.evidence.documentStepTrace()));
            for (DocumentStepEvidence step
                    : capture.evidence.documentStepTrace()) {
                assertEquals(step.targetDocumentId(),
                        step.executionRootDocumentId());
                assertEquals("/", step.scopePath());
                assertEquals("ISOLATED_DOCUMENT", step.executionMode());
                assertTrue(step.ambientContainingDocumentIds().isEmpty());
            }
            assertEquals(2,
                    capture.evidence.tentativeFinalizations().size());
            assertTrue(capture.evidence
                    .managedDocumentStepInclusiveNanos() > 0L);
            assertTrue(capture.evidence
                    .managedDocumentStepExclusiveNanos() >= 0L);
            assertTrue(capture.evidence
                    .componentFinalizationProofNanos() > 0L);
            assertTrue(capture.evidence
                    .successfulResultAssemblyNanos() > 0L);
            assertEquals(TentativeFinalization.Boundary.Kind.WORK,
                    capture.evidence.tentativeFinalizations().get(0)
                            .boundary().kind());
            assertEquals(
                    TentativeFinalization.Boundary.Kind.INITIALIZATION_BATCH,
                    capture.evidence.tentativeFinalizations().get(1)
                            .boundary().kind());
            for (ResultingDocument document
                    : attempt.processResult().resultingDocuments()) {
                assertTrue(document.initialized());
                assertNotNull(document.document().getContracts()
                        .getProperties().get("initialized"));
                assertEquals(0L, document.epoch());
            }
        }
    }

    @Test
    void chargesExactMarkerIdentityWorkAndRollsBackARejectedBatch() {
        // given
        try (DocumentProcessor owner = DocumentProcessor.builder().build()) {
            ClosureAttemptResult baseline;
            try (BlueClosureContracts contracts =
                         new BlueClosureContracts(owner)) {
                baseline = contracts.admitClosure(admission(
                        owner, 100000L, 128L));
            }
            List<GasTraceEntry> exactMarkerGas = markerGas(
                    baseline.processResult().gasTrace());
            long limitThroughSecondMarker = limitThroughSecondMarker(
                    baseline.processResult().gasTrace());
            Capture rejectedCapture = new Capture();

            // when
            ClosureAttemptResult rejected;
            try (BlueClosureContracts contracts =
                         new BlueClosureContracts(owner, rejectedCapture)) {
                rejected = contracts.admitClosure(admission(
                        owner, limitThroughSecondMarker, 128L));
            }

            // then
            assertEquals(20, exactMarkerGas.size());
            assertExactMarkerGas(exactMarkerGas, 0, A);
            assertExactMarkerGas(exactMarkerGas, 10, B);
            assertEquals(ProcessorStatus.GAS_LIMIT_EXCEEDED,
                    rejected.processResult().status());
            assertFalse(rejected.processResult().commits());
            RejectedCharge rejectedCharge = rejected.processResult()
                    .rejectedCharge();
            assertEquals(GasTraceEntry.Namespace.SEMANTIC,
                    rejectedCharge.namespace());
            assertEquals("nodeIdentityEstablished",
                    rejectedCharge.counter());
            assertEquals(1L, rejectedCharge.quantity());
            assertEquals(1L, rejectedCharge.subtotal());
            assertEquals(0L, rejectedCharge.remainingBeforeCharge());
            assertEquals(RejectedCharge.ApplicableCap.Kind.SHARED,
                    rejectedCharge.applicableCap().kind());
            assertEquals(RejectedCharge.Owner.Kind.INVOCATION,
                    rejectedCharge.owner().kind());
            List<GasTraceEntry> rejectedMarkerGas = markerGas(
                    rejected.processResult().gasTrace());
            assertEquals(11, rejectedMarkerGas.size());
            assertExactMarkerGas(rejectedMarkerGas, 0, A);
            assertEquals(B, rejectedMarkerGas.get(10).documentId());
            assertEquals("processorMarkerWritten",
                    rejectedMarkerGas.get(10).counter());
            assertEquals(20L, rejectedMarkerGas.get(10).subtotal());
            assertNotNull(rejectedCapture.evidence);
            assertEquals(1,
                    rejectedCapture.evidence.tentativeFinalizations().size());
            assertEquals(TentativeFinalization.Boundary.Kind.WORK,
                    rejectedCapture.evidence.tentativeFinalizations().get(0)
                            .boundary().kind());
            for (ResultingDocument document
                    : rejected.processResult().resultingDocuments()) {
                assertFalse(document.initialized());
                assertEquals(document.beforeBlueId(), document.afterBlueId());
                Node contracts = document.document().getContracts();
                assertTrue(contracts == null
                        || contracts.getProperties() == null
                        || !contracts.getProperties().containsKey(
                                ProcessorContractConstants.KEY_INITIALIZED));
            }
        }
    }

    @Test
    void rebindsInactiveProspectiveRowsToThePostAdmissionTargetHeadOnly() {
        try (DocumentProcessor owner = DocumentProcessor.builder().build()) {
            ClosureInvocationInput input = admission(
                    owner, 100000L, 128L, null, true);
            ManagedOccurrenceBinding inputProspective = binding(
                    input.snapshot().occurrences(), A, "/b");
            ManagedOccurrenceBinding inputHistorical = binding(
                    input.snapshot().occurrences(), A, "/history");
            ManagedOccurrenceBinding inputActive = binding(
                    input.snapshot().occurrences(), B, "/a");

            ClosureAttemptResult attempt;
            try (BlueClosureContracts contracts =
                         new BlueClosureContracts(owner)) {
                attempt = contracts.admitClosure(input);
            }

            assertTrue(attempt.isComplete());
            assertEquals(ProcessorStatus.SUCCESS,
                    attempt.processResult().status(), diagnostic(attempt));
            ClosureProcessResult result = attempt.processResult();
            String resultingA = document(result, A).afterBlueId();
            String resultingB = document(result, B).afterBlueId();
            ManagedOccurrenceBinding prospective = binding(
                    result.occurrenceBindings(), A, "/b");
            ManagedOccurrenceBinding historical = binding(
                    result.occurrenceBindings(), A, "/history");
            ManagedOccurrenceBinding active = binding(
                    result.occurrenceBindings(), B, "/a");

            assertFalse(prospective.active());
            assertNull(prospective.pendingHistoricalEpoch());
            assertEquals(resultingB, prospective.expectedTargetBlueId());
            assertEquals(inputProspective.occurrenceIdentity(),
                    prospective.occurrenceIdentity());
            assertNotEquals(inputProspective.bindingIdentity(),
                    prospective.bindingIdentity());

            assertFalse(historical.active());
            assertEquals(Long.valueOf(7L),
                    historical.pendingHistoricalEpoch());
            assertEquals(inputHistorical.expectedTargetBlueId(),
                    historical.expectedTargetBlueId());
            assertEquals(inputHistorical.occurrenceIdentity(),
                    historical.occurrenceIdentity());
            assertEquals(inputHistorical.bindingIdentity(),
                    historical.bindingIdentity());

            assertTrue(active.active());
            assertNull(active.pendingHistoricalEpoch());
            assertEquals(resultingA, active.expectedTargetBlueId());
            assertEquals(inputActive.occurrenceIdentity(),
                    active.occurrenceIdentity());
        }
    }

    @Test
    void rejectsRecomputedPolicyThatInflatesOneFrozenLimitBeforeAdmission() {
        try (DocumentProcessor owner = DocumentProcessor.builder().build()) {
            Capture capture = new Capture();
            IllegalArgumentException rejection;
            try (BlueClosureContracts contracts =
                         new BlueClosureContracts(owner, capture)) {
                rejection = assertThrows(
                        IllegalArgumentException.class,
                        () -> contracts.admitClosure(admission(
                                owner, 100000L, 129L)));
            }

            assertEquals(
                    "Invocation portable limits are not the frozen "
                            + "Contracts 1.0 policy",
                    rejection.getMessage());
            assertNull(capture.evidence);
        }
    }

    @Test
    void reportsInvocationAndFinalizationGasOwnersWithAtomicRollback() {
        try (DocumentProcessor owner = DocumentProcessor.builder().build()) {
            ClosureAttemptResult invocationRejected;
            try (BlueClosureContracts contracts =
                         new BlueClosureContracts(owner)) {
                invocationRejected = contracts.admitClosure(admission(
                        owner, 0L, 128L));
            }
            assertEquals(ProcessorStatus.GAS_LIMIT_EXCEEDED,
                    invocationRejected.processResult().status());
            assertEquals(RejectedCharge.Owner.Kind.INVOCATION,
                    invocationRejected.processResult().rejectedCharge()
                            .owner().kind());
            assertEquals("processInvocation",
                    invocationRejected.processResult().rejectedCharge()
                            .counter());

            ClosureAttemptResult baseline;
            try (BlueClosureContracts contracts =
                         new BlueClosureContracts(owner)) {
                baseline = contracts.admitClosure(admission(
                        owner, 100000L, 128L));
            }
            long admitted = 0L;
            for (GasTraceEntry entry
                    : baseline.processResult().gasTrace()) {
                if ("tentativeComponentFinalization".equals(
                        entry.counter())) {
                    break;
                }
                admitted += entry.subtotal();
            }
            ClosureAttemptResult finalizationRejected;
            Capture capture = new Capture();
            try (BlueClosureContracts contracts =
                         new BlueClosureContracts(owner, capture)) {
                finalizationRejected = contracts.admitClosure(admission(
                        owner, admitted + 19L, 128L));
            }
            assertEquals(ProcessorStatus.GAS_LIMIT_EXCEEDED,
                    finalizationRejected.processResult().status());
            assertFalse(finalizationRejected.processResult().commits());
            RejectedCharge rejected = finalizationRejected
                    .processResult().rejectedCharge();
            assertEquals("tentativeComponentFinalization",
                    rejected.counter());
            assertEquals(RejectedCharge.Owner.Kind.FINALIZATION,
                    rejected.owner().kind());
            assertEquals(Long.valueOf(0L),
                    rejected.owner().finalizationOrdinal());
            assertEquals(1, capture.evidence.workTrace().size());
            assertEquals(1, capture.evidence.documentStepTrace().size());
            assertTrue(capture.evidence.tentativeFinalizations().isEmpty());
            for (ResultingDocument document : finalizationRejected
                    .processResult().resultingDocuments()) {
                assertFalse(document.initialized());
                assertEquals(document.beforeBlueId(), document.afterBlueId());
            }
        }
    }

    @Test
    void providerSuspensionPublishesNoAdmissionCompletionEvidence() {
        ContractProcessorRegistry registry =
                ContractProcessorRegistryBuilder.create()
                        .register(
                                SUSPENDING_CHANNEL_BLUE_ID,
                                SUSPENDING_CHANNEL_TYPE,
                                new SuspendingChannelProcessor())
                        .register(
                                SUSPENDING_HANDLER_BLUE_ID,
                                SUSPENDING_HANDLER_TYPE,
                                new SuspendingHandlerProcessor())
                        .build();
        NodeProvider provider = new NodeProvider() {
            @Override
            public List<Node> fetchByBlueId(String blueId) {
                return BlueRuntimeTypeRegistry.getDefault()
                        .asProvider().fetchByBlueId(blueId);
            }

            @Override
            public NodeProviderResult fetchResultByBlueId(String blueId) {
                if (UNAVAILABLE_BLUE_ID.equals(blueId)) {
                    return NodeProviderResult.unavailable(
                            "Exact admission resource is temporarily unavailable");
                }
                return NodeProvider.super.fetchResultByBlueId(blueId);
            }
        };
        try (DocumentProcessor owner = DocumentProcessor.builder()
                .runtimeRegistry(registry)
                .nodeProvider(provider)
                .build()) {
            Capture capture = new Capture();
            ClosureAttemptResult attempt;
            try (BlueClosureContracts contracts =
                         new BlueClosureContracts(owner, capture)) {
                attempt = contracts.admitClosure(admission(
                        owner, 100000L, 128L,
                        UNAVAILABLE_BLUE_ID));
            }

            assertEquals(ClosureAttemptResult.Kind.NEEDS_RESOURCES,
                    attempt.kind(), diagnostic(attempt));
            assertEquals(Collections.singletonList(UNAVAILABLE_BLUE_ID),
                    attempt.requiredExactBlueIds());
            assertNull(capture.evidence,
                    "A suspended admission must not publish completion evidence");
        }
    }

    private static ClosureInvocationInput admission(
            DocumentProcessor owner,
            long sharedLimit,
            long cyclicMemberLimit) {
        return admission(owner, sharedLimit, cyclicMemberLimit, null);
    }

    private static ClosureInvocationInput admission(
            DocumentProcessor owner,
            long sharedLimit,
            long cyclicMemberLimit,
            String unavailableBlueId) {
        return admission(owner, sharedLimit, cyclicMemberLimit,
                unavailableBlueId, false);
    }

    private static ClosureInvocationInput admission(
            DocumentProcessor owner,
            long sharedLimit,
            long cyclicMemberLimit,
            String unavailableBlueId,
            boolean prospective) {
        ClosureEnvironment.LabeledIdentityEvidence documentPolicy = labeled(
                ClosureIdentityService.Constructor
                        .MANAGED_DOCUMENT_IDENTITY_POLICY,
                "admission-test-document-lineage");
        ClosureEnvironment.LabeledIdentityEvidence bindingPolicy = labeled(
                ClosureIdentityService.Constructor.MANAGED_BINDING_POLICY,
                "admission-test-binding-lineage");
        AffectedClosureSnapshot snapshot = prospective
                ? prospectiveSnapshot(bindingPolicy.identity())
                : cyclicSnapshot(
                        bindingPolicy.identity(), unavailableBlueId);
        AdmissionCause provisionalCause = new AdmissionCause(
                hash('1'),
                AdmissionKind.TOP_LEVEL_ADMISSION,
                "cyclic-admission-test",
                null,
                null,
                hash('2'));
        AdmissionCause cause = new AdmissionCause(
                IDENTITIES.causeIdentity(provisionalCause),
                provisionalCause.admissionKind(),
                provisionalCause.label(),
                null,
                null,
                provisionalCause.policyIdentity());
        ExecutionPolicy provisionalPolicy = new ExecutionPolicy(
                hash('3'), sharedLimit,
                Collections.<DocumentId, Long>emptyMap(),
                "admission-test-gas");
        ExecutionPolicy policy = new ExecutionPolicy(
                IDENTITIES.executionPolicyIdentity(provisionalPolicy),
                sharedLimit,
                provisionalPolicy.localLimits(),
                provisionalPolicy.label());
        ClosureEnvironment.PortableLimitPolicyEvidence portable =
                portable(cyclicMemberLimit);
        ClosureRuntimeDescriptor runtime =
                ClosureRuntimeDescriptor.capture(owner);
        ClosureEnvironment environment = new ClosureEnvironment(
                hash('4'),
                hash('5'),
                runtime.runtimeRegistryIdentity(),
                runtime.gasManifestIdentity(),
                documentPolicy,
                bindingPolicy,
                labeled(ClosureIdentityService.Constructor
                        .EXACT_NODE_PROVIDER_DOMAIN,
                        "admission-test-provider"),
                labeled(ClosureIdentityService.Constructor
                        .EXTERNAL_ORDER_POLICY,
                        "admission-test-order"),
                portable,
                ClosureRuntimeDescriptor.CYCLIC_FINALIZER_IDENTITY,
                ClosureRuntimeDescriptor.CYCLIC_PROOF_VERIFIER_IDENTITY);
        String deliveries = IDENTITIES.directDeliverySnapshotIdentity(
                Collections.<DirectLogicalDelivery>emptyList());
        ClosureInvocationInput provisional =
                ClosureInvocationInput.admitClosure(
                        hash('6'), snapshot, cause, null, null,
                        deliveries, policy, environment);
        return ClosureInvocationInput.admitClosure(
                IDENTITIES.invocationIdentity(provisional),
                snapshot, cause, null, null,
                deliveries, policy, environment);
    }

    private static AffectedClosureSnapshot cyclicSnapshot(
            String bindingPolicyIdentity) {
        return cyclicSnapshot(bindingPolicyIdentity, null);
    }

    private static AffectedClosureSnapshot cyclicSnapshot(
            String bindingPolicyIdentity,
            String unavailableBlueId) {
        Node placeholderA = new Node()
                .name("Admission A")
                .properties("b", new Node().blueId("this#1"))
                .contracts(processEmbedded("/b"));
        if (unavailableBlueId != null) {
            placeholderA.getContracts().properties(
                    "requiredResource",
                    new Node()
                            .type(new Node().blueId(
                                    SUSPENDING_CHANNEL_BLUE_ID))
                            .properties(
                                    "resource",
                                    new Node().blueId(
                                            unavailableBlueId)))
                    .properties(
                            "requiredResourceHandler",
                            new Node()
                                    .type(new Node().blueId(
                                            SUSPENDING_HANDLER_BLUE_ID))
                                    .properties(
                                            "channel",
                                            new Node().value(
                                                    "requiredResource")));
        }
        Node placeholderB = new Node()
                .name("Admission B")
                .properties("a", new Node().blueId("this#0"))
                .contracts(processEmbedded("/a"));
        CyclicSetFinalization language =
                new CircularSetIdentityCalculator().finalizeCyclicSet(
                        Arrays.asList(placeholderA, placeholderB));
        List<String> canonicalBlueIds = new ArrayList<String>();
        for (CyclicMemberFinalization member
                : language.membersInCanonicalOrder()) {
            canonicalBlueIds.add(member.finalBlueId());
        }
        Node bodyA = language.membersInInputOrder().get(0)
                .canonicalMemberBody();
        Node bodyB = language.membersInInputOrder().get(1)
                .canonicalMemberBody();
        materializeThis(bodyA, canonicalBlueIds);
        materializeThis(bodyB, canonicalBlueIds);
        String blueA = language.membersInInputOrder().get(0).finalBlueId();
        String blueB = language.membersInInputOrder().get(1).finalBlueId();
        List<ManagedOccurrenceBinding> bindings = Arrays.asList(
                ManagedOccurrenceBinding.derived(
                        bindingPolicyIdentity,
                        A,
                        ScopeAddress.embedded("/b", 1L),
                        B,
                        blueB,
                        true,
                        null),
                ManagedOccurrenceBinding.derived(
                        bindingPolicyIdentity,
                        B,
                        ScopeAddress.embedded("/a", 1L),
                        A,
                        blueA,
                        true,
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
        bodies.put(A, bodyA);
        bodies.put(B, bodyB);
        ComponentFinalizationResult exact =
                new ComponentFinalizationKernel().finalizeComponents(
                        new ComponentFinalizationInput(
                                graph, generations, bodies, bindings));
        ArrayList<ManagedDocumentSnapshot> documents =
                new ArrayList<ManagedDocumentSnapshot>();
        documents.add(new ManagedDocumentSnapshot(
                A, exact.document(A).blueId(), exact.document(A).document(),
                false, false, true, 0L, 1L));
        documents.add(new ManagedDocumentSnapshot(
                B, exact.document(B).blueId(), exact.document(B).document(),
                false, false, false, 0L, 1L));
        ArrayList<ComponentSnapshot> components =
                new ArrayList<ComponentSnapshot>();
        for (FinalizedComponentEvidence component : exact.components()) {
            components.add(component.component());
        }
        List<ManagedOccurrenceBinding> exactBindings =
                exact.finalizedGraph().bindings();
        String bindingSet = IDENTITIES.occurrenceBindingSetIdentity(
                exactBindings);
        AffectedClosureSnapshot provisional = new AffectedClosureSnapshot(
                hash('0'), 1L, documents, exactBindings, bindingSet,
                components, Collections.singletonList(A));
        return new AffectedClosureSnapshot(
                IDENTITIES.affectedClosureIdentity(provisional),
                1L, documents, exactBindings, bindingSet,
                components, Collections.singletonList(A));
    }

    private static AffectedClosureSnapshot prospectiveSnapshot(
            String bindingPolicyIdentity) {
        Node bodyA = new Node()
                .name("Prospective admission A")
                .contracts(processEmbedded("/b", "/history"));
        Node bodyB = new Node()
                .name("Prospective admission B")
                .properties("a", new Node().blueId(
                        DirectBlueIdCalculator.calculateBlueId(bodyA)))
                .contracts(processEmbedded("/a"));
        String provisionalA = DirectBlueIdCalculator.calculateBlueId(bodyA);
        String provisionalB = DirectBlueIdCalculator.calculateBlueId(bodyB);
        List<ManagedOccurrenceBinding> provisionalBindings = Arrays.asList(
                ManagedOccurrenceBinding.derived(
                        bindingPolicyIdentity,
                        A,
                        ScopeAddress.embedded("/b", 1L),
                        B,
                        provisionalB,
                        false,
                        null),
                ManagedOccurrenceBinding.derived(
                        bindingPolicyIdentity,
                        A,
                        ScopeAddress.embedded("/history", 1L),
                        B,
                        provisionalB,
                        false,
                        Long.valueOf(7L)),
                ManagedOccurrenceBinding.derived(
                        bindingPolicyIdentity,
                        B,
                        ScopeAddress.embedded("/a", 1L),
                        A,
                        provisionalA,
                        true,
                        null));
        LinkedHashMap<DocumentId, Long> generations =
                new LinkedHashMap<DocumentId, Long>();
        generations.put(A, Long.valueOf(1L));
        generations.put(B, Long.valueOf(1L));
        LinkedHashMap<DocumentId, Node> bodies =
                new LinkedHashMap<DocumentId, Node>();
        bodies.put(A, bodyA);
        bodies.put(B, bodyB);
        ComponentFinalizationResult provisional =
                new ComponentFinalizationKernel().finalizeComponents(
                        new ComponentFinalizationInput(
                                ManagedDocumentGraph.fromBindings(
                                        Arrays.asList(A, B),
                                        provisionalBindings),
                                generations,
                                bodies,
                                provisionalBindings));
        List<ManagedOccurrenceBinding> exactBindings = Arrays.asList(
                ManagedOccurrenceBinding.derived(
                        bindingPolicyIdentity,
                        A,
                        ScopeAddress.embedded("/b", 1L),
                        B,
                        provisional.document(B).blueId(),
                        false,
                        null),
                ManagedOccurrenceBinding.derived(
                        bindingPolicyIdentity,
                        A,
                        ScopeAddress.embedded("/history", 1L),
                        B,
                        provisional.document(B).blueId(),
                        false,
                        Long.valueOf(7L)),
                ManagedOccurrenceBinding.derived(
                        bindingPolicyIdentity,
                        B,
                        ScopeAddress.embedded("/a", 1L),
                        A,
                        provisional.document(A).blueId(),
                        true,
                        null));
        Collections.sort(exactBindings);
        ComponentFinalizationResult exact =
                new ComponentFinalizationKernel().finalizeComponents(
                        new ComponentFinalizationInput(
                                ManagedDocumentGraph.fromBindings(
                                        Arrays.asList(A, B),
                                        exactBindings),
                                generations,
                                bodies,
                                exactBindings));
        ArrayList<ManagedDocumentSnapshot> documents =
                new ArrayList<ManagedDocumentSnapshot>();
        documents.add(new ManagedDocumentSnapshot(
                A, exact.document(A).blueId(), exact.document(A).document(),
                false, false, true, 0L, 1L));
        documents.add(new ManagedDocumentSnapshot(
                B, exact.document(B).blueId(), exact.document(B).document(),
                false, false, false, 0L, 1L));
        ArrayList<ComponentSnapshot> components =
                new ArrayList<ComponentSnapshot>();
        for (FinalizedComponentEvidence component : exact.components()) {
            components.add(component.component());
        }
        exactBindings = exact.finalizedGraph().bindings();
        String bindingSet = IDENTITIES.occurrenceBindingSetIdentity(
                exactBindings);
        AffectedClosureSnapshot provisionalSnapshot =
                new AffectedClosureSnapshot(
                        hash('0'), 1L, documents, exactBindings, bindingSet,
                        components, Collections.singletonList(A));
        return new AffectedClosureSnapshot(
                IDENTITIES.affectedClosureIdentity(provisionalSnapshot),
                1L, documents, exactBindings, bindingSet,
                components, Collections.singletonList(A));
    }

    private static void materializeThis(
            Node node,
            List<String> canonicalBlueIds) {
        if (node == null) {
            return;
        }
        String blueId = node.getBlueId();
        if (blueId != null && blueId.startsWith("this#")) {
            node.blueId(canonicalBlueIds.get(
                    Integer.parseInt(blueId.substring(5))));
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

    private static ClosureEnvironment.PortableLimitPolicyEvidence portable(
            long cyclicMemberLimit) {
        LinkedHashMap<String, Long> limits =
                new LinkedHashMap<String, Long>(
                        GasSchedule.contracts10().portableLimits());
        limits.put("cyclicMembersPerComponent",
                Long.valueOf(cyclicMemberLimit));
        ClosureEnvironment.PortableLimitPolicyEvidence provisional =
                new ClosureEnvironment.PortableLimitPolicyEvidence(
                        hash('7'), "admission-test-limits", limits);
        return new ClosureEnvironment.PortableLimitPolicyEvidence(
                IDENTITIES.portableLimitPolicyIdentity(provisional),
                provisional.label(),
                provisional.limits());
    }

    private static Node processEmbedded(String... paths) {
        ArrayList<Node> items = new ArrayList<Node>();
        for (String path : paths) {
            items.add(new Node().value(path));
        }
        return new Node().properties(
                "embedded",
                new Node()
                        .type(new Node().blueId(
                                RuntimeBlueIds.PROCESS_EMBEDDED))
                        .properties(
                                "paths",
                                new Node().items(items)));
    }

    private static ManagedOccurrenceBinding binding(
            List<ManagedOccurrenceBinding> bindings,
            DocumentId source,
            String path) {
        for (ManagedOccurrenceBinding binding : bindings) {
            if (source.equals(binding.sourceDocumentId())
                    && path.equals(binding.sourcePath())) {
                return binding;
            }
        }
        throw new AssertionError("Missing occurrence "
                + source.value() + ":" + path);
    }

    private static ResultingDocument document(
            ClosureProcessResult result,
            DocumentId documentId) {
        for (ResultingDocument document : result.resultingDocuments()) {
            if (documentId.equals(document.documentId())) {
                return document;
            }
        }
        throw new AssertionError("Missing document " + documentId.value());
    }

    private static List<GasTraceEntry> markerGas(
            List<GasTraceEntry> trace) {
        ArrayList<GasTraceEntry> result = new ArrayList<GasTraceEntry>();
        for (GasTraceEntry entry : trace) {
            if ("processorMarkerWritten".equals(entry.counter())
                    || "identity-rebuild".equals(entry.reason())) {
                result.add(entry);
            }
        }
        return result;
    }

    private static long limitThroughSecondMarker(
            List<GasTraceEntry> trace) {
        long admitted = 0L;
        int markerCount = 0;
        for (GasTraceEntry entry : trace) {
            admitted += entry.subtotal();
            if ("processorMarkerWritten".equals(entry.counter())) {
                markerCount++;
                if (markerCount == 2) {
                    return admitted;
                }
            }
        }
        throw new AssertionError("Missing second initialization marker");
    }

    private static void assertExactMarkerGas(
            List<GasTraceEntry> trace,
            int offset,
            DocumentId documentId) {
        String[] counters = {
                "processorMarkerWritten",
                "nodeIdentityEstablished",
                "objectMemberRebuilt",
                "directIdentityHashBlock",
                "nodeIdentityEstablished",
                "objectMemberRebuilt",
                "directIdentityHashBlock",
                "nodeIdentityEstablished",
                "objectMemberRebuilt",
                "directIdentityHashBlock"
        };
        long[] quantities = {1L, 1L, 2L, 3L, 1L, 2L, 3L, 1L, 3L, 3L};
        long[] subtotals = {20L, 1L, 2L, 3L, 1L, 2L, 3L, 1L, 3L, 3L};
        for (int index = 0; index < counters.length; index++) {
            GasTraceEntry entry = trace.get(offset + index);
            assertEquals(documentId, entry.documentId());
            assertEquals(counters[index], entry.counter());
            assertEquals(quantities[index], entry.quantity());
            assertEquals(subtotals[index], entry.subtotal());
            assertEquals(index == 0
                            ? GasTraceEntry.Namespace.PROCESSOR
                            : GasTraceEntry.Namespace.SEMANTIC,
                    entry.namespace());
            assertEquals(index == 0
                            ? "initialization-batch.marker."
                                    + documentId.value()
                            : "identity-rebuild",
                    entry.reason());
            assertEquals("/", entry.scopePath());
            assertEquals(Long.valueOf(0L),
                    entry.activationGeneration());
            assertEquals(Long.valueOf(1L),
                    entry.componentGeneration());
            assertEquals(index == 0
                            ? null
                            : ProcessorPointerConstants.RELATIVE_INITIALIZED,
                    entry.logicalPath());
            assertNull(entry.contractKey());
            assertNull(entry.workOccurrenceId());
        }
    }

    private static ClosureEnvironment.LabeledIdentityEvidence labeled(
            ClosureIdentityService.Constructor constructor,
            String label) {
        return new ClosureEnvironment.LabeledIdentityEvidence(
                IDENTITIES.labeledIdentity(constructor, label),
                label);
    }

    private static List<DocumentId> targets(
            List<DocumentStepEvidence> steps) {
        ArrayList<DocumentId> result = new ArrayList<DocumentId>();
        for (DocumentStepEvidence step : steps) {
            result.add(step.targetDocumentId());
        }
        return result;
    }

    private static String diagnostic(ClosureAttemptResult attempt) {
        if (!attempt.isComplete()) {
            return "needs resources " + attempt.requiredExactBlueIds();
        }
        return attempt.processResult().diagnostic() == null
                ? null : attempt.processResult().diagnostic().message();
    }

    private static String hash(char value) {
        StringBuilder result = new StringBuilder("sha256:");
        for (int index = 0; index < 64; index++) {
            result.append(value);
        }
        return result.toString();
    }

    /** Application channel used to exercise exact provider suspension. */
    public static final class SuspendingChannel extends ChannelContract {
        private UnavailableResource resource;

        /** Returns the exact resource reference consulted by the runtime. */
        public UnavailableResource getResource() {
            return resource;
        }

        /** Sets the exact resource reference consulted by the runtime. */
        public void setResource(UnavailableResource value) {
            resource = value;
        }
    }

    /** Typed mapped value whose exact body is intentionally unavailable. */
    public static final class UnavailableResource {
        private String value;

        /** Returns the mapped marker. */
        public String getValue() {
            return value;
        }

        /** Sets the mapped marker. */
        public void setValue(String marker) {
            value = marker;
        }
    }

    private static final class SuspendingChannelProcessor
            implements ChannelProcessor<SuspendingChannel> {
        private final ExternalChannelSubscriptionFunctions<
                SuspendingChannel> functions =
                new ExternalChannelSubscriptionFunctions<
                        SuspendingChannel>() {
                    @Override
                    public List<String> channelKeys(
                            SuspendingChannel channel,
                            ExternalChannelFunctionContext context) {
                        if (channel.getResource() == null) {
                            throw new IllegalStateException(
                                    "Mapped resource must be present");
                        }
                        return Collections.singletonList(
                                "admission-resource");
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            SuspendingChannel channel) {
                        return "admission-resource-v1";
                    }
                };

        @Override
        public Class<SuspendingChannel> contractType() {
            return SuspendingChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<SuspendingChannel>
        externalSubscriptionFunctions() {
            return functions;
        }
    }

    /** Application handler binding the admission resource channel. */
    public static final class SuspendingHandler extends HandlerContract {
    }

    private static final class SuspendingHandlerProcessor
            implements HandlerProcessor<SuspendingHandler> {
        @Override
        public Class<SuspendingHandler> contractType() {
            return SuspendingHandler.class;
        }

        @Override
        public void execute(
                SuspendingHandler contract,
                ProcessorExecutionContext context) {
            // No event is delivered during admission.
        }
    }

    private static final class Capture implements ClosureExecutionObserver {
        private ClosureImplementationEvidence evidence;

        @Override
        public void onExecutionEvidence(
                ClosureImplementationEvidence value) {
            evidence = value;
        }
    }
}
