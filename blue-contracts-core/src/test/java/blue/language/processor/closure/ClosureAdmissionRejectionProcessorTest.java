package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.processor.ClosureRuntimeDescriptor;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.GasSchedule;
import blue.language.processor.ProcessorErrorCategory;
import blue.language.processor.ProcessorStatus;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Released C-CLO-14/15/29 production admission-rejection vectors. */
final class ClosureAdmissionRejectionProcessorTest {

    private static final ClosureIdentityService IDENTITIES =
            ClosureIdentityService.INSTANCE;
    private static final String BINDING_POLICY_LABEL =
            "exact-document-lineage";
    private static final String BINDING_POLICY =
            "sha256:c1e8d880499cbafc595e1fb213ee73acc6ddb8d1d9850c7ddff2224c88a03d35";
    private static final String MASTER =
            "9J59XHipxtMwoMTY7r7HaicepUCtDPkMPFMAzLfdp4iW";
    private static final DocumentId SIMPLE_A = new DocumentId("simple-a");
    private static final DocumentId SIMPLE_B = new DocumentId("simple-b");

    @Test
    void rejectsCclo14BadCyclicProofAtTheMasterComparison() {
        try (DocumentProcessor owner = DocumentProcessor.builder().build()) {
            CyclicState state = cyclicState();
            ComponentSnapshot component = state.snapshot.components().get(0);
            AdmissionCandidate candidate = AdmissionCandidate.badCyclicProof(
                    new AdmissionCandidate.CandidateCyclicProof(
                            component.componentIdentity(),
                            "6Rnjv8oquG4RqPwo55MZgF7jcUZGd7HdYZMPiFmBQUQ7",
                            memberStates(component),
                            component.completeCyclicProof()
                                    .declaredPlaceholderSet()));

            ClosureProcessResult result = reject(
                    owner, admission(owner, state.snapshot, candidate));

            assertRollback(
                    state.snapshot,
                    result,
                    199L,
                    ProcessorErrorCategory.CyclicSetProofInvalid);
            assertTraceShape(result, Arrays.asList(
                    entry("processor", "processInvocation", 1L),
                    entry("processor", "closureInvocation", 1L),
                    entry("processor", "managedDocumentOpened", 1L),
                    entry("processor", "managedDocumentOpened", 1L),
                    entry("processor", "managedOccurrenceBindingVerified", 1L),
                    entry("processor", "processEmbeddedEdgeExamined", 1L),
                    entry("processor", "managedOccurrenceBindingVerified", 1L),
                    entry("processor", "processEmbeddedEdgeExamined", 1L),
                    entry("processor", "componentMemberPartitioned", 1L),
                    entry("processor", "componentMemberPartitioned", 1L),
                    entry("processor", "componentEdgePartitioned", 1L),
                    entry("processor", "componentEdgePartitioned", 1L),
                    entry("semantic", "validationMemberExamined", 1L),
                    entry("semantic", "scalarComparison", 1L),
                    entry("semantic", "textBlockExamined", 4L),
                    entry("semantic", "scalarComparison", 1L),
                    entry("semantic", "textBlockExamined", 2L)));
            assertEquals(
                    "sha256:b75dc054efedd82d9406dd8b5b6060686146b381cb1ae1acf14e01e1f4e818b1",
                    result.gasTraceIdentity());
        }
    }

    @Test
    void rejectsCclo15FromTheRealPreliminaryIdentityCalculation() {
        try (DocumentProcessor owner = DocumentProcessor.builder().build()) {
            AffectedClosureSnapshot snapshot = acyclicSnapshot(
                    new DocumentId("amb-a"),
                    new Node().properties(
                            "same", new Node().value(Boolean.TRUE)));
            AdmissionCandidate candidate =
                    AdmissionCandidate.ambiguousPreliminaryMembers(
                            Arrays.asList(
                                    new AdmissionCandidate
                                            .CandidateCyclicMember(
                                                    new DocumentId("amb-a"),
                                                    ambiguousMember("this#1")),
                                    new AdmissionCandidate
                                            .CandidateCyclicMember(
                                                    new DocumentId("amb-b"),
                                                    ambiguousMember("this#0"))));

            ClosureProcessResult result = reject(
                    owner, admission(owner, snapshot, candidate));

            assertRollback(
                    snapshot,
                    result,
                    181L,
                    ProcessorErrorCategory
                            .CyclicPreliminaryMemberAmbiguous);
            assertTraceShape(result, Arrays.asList(
                    entry("processor", "processInvocation", 1L),
                    entry("processor", "closureInvocation", 1L),
                    entry("processor", "managedDocumentOpened", 1L),
                    entry("processor", "componentMemberPartitioned", 1L),
                    entry("semantic", "validationMemberExamined", 1L),
                    entry("semantic", "validationMemberExamined", 1L),
                    entry("semantic", "nodeIdentityEstablished", 1L),
                    entry("semantic", "objectMemberRebuilt", 2L),
                    entry("semantic", "directIdentityHashBlock", 3L),
                    entry("semantic", "sortComparison", 1L),
                    entry("semantic", "scalarComparison", 1L),
                    entry("semantic", "textBlockExamined", 2L),
                    entry("semantic", "scalarComparison", 1L),
                    entry("semantic", "textBlockExamined", 6L)));
            assertEquals(
                    "sha256:e83c2e9dd00038960571a949a558ffe1f809496f1151a74c9dae194b1a81a29e",
                    result.gasTraceIdentity());
        }
    }

    @Test
    void rejectsCclo29AfterTraversingTheActualMissingSourcePath() {
        try (DocumentProcessor owner = DocumentProcessor.builder().build()) {
            CyclicState state = cyclicState();
            String targetBlueId = state.snapshot
                    .managedDocument(SIMPLE_B).blueId();
            ScopeAddress address = ScopeAddress.embedded("/missing", 1L);
            AdmissionCandidate candidate =
                    AdmissionCandidate.invalidOccurrenceBinding(
                            Collections.singletonList(
                                    new AdmissionCandidate
                                            .CandidateOccurrenceBinding(
                                                    IDENTITIES
                                                            .managedOccurrenceIdentity(
                                                                    SIMPLE_A,
                                                                    address,
                                                                    SIMPLE_B,
                                                                    BINDING_POLICY),
                                                    IDENTITIES
                                                            .managedOccurrenceBindingIdentity(
                                                                    SIMPLE_A,
                                                                    address,
                                                                    SIMPLE_B,
                                                                    targetBlueId,
                                                                    BINDING_POLICY),
                                                    BINDING_POLICY,
                                                    SIMPLE_A,
                                                    "/missing",
                                                    1L,
                                                    SIMPLE_B,
                                                    targetBlueId,
                                                    true)));

            ClosureProcessResult result = reject(
                    owner, admission(owner, state.snapshot, candidate));

            assertRollback(
                    state.snapshot,
                    result,
                    196L,
                    ProcessorErrorCategory.ManagedOccurrenceBindingMissing);
            assertEquals(14, result.gasTrace().size());
            assertEntry(result.gasTrace().get(12),
                    "processor", "managedOccurrenceBindingVerified", 1L);
            assertEntry(result.gasTrace().get(13),
                    "processor", "pointerSegmentTraversed", 1L);
            assertEquals(
                    "sha256:5a036f7108917c4a5ffba520ff652929a3282c503ca836f6e2090adad0871f9f",
                    result.gasTraceIdentity());
        }
    }

    private static ClosureProcessResult reject(
            DocumentProcessor owner, ClosureInvocationInput input) {
        ClosureInvocationVerifier.Verification verification =
                ClosureInvocationVerifier.verify(
                        input, owner.administration()::runtimeAccess);
        assertEquals(
                ClosureInvocationVerifier.CandidateDisposition
                        .SEMANTICALLY_INVALID,
                verification.candidateDisposition());
        assertTrue(ClosureAdmissionRejectionProcessor.supports(
                input, verification));
        ClosureAttemptResult attempt;
        Capture capture = new Capture();
        try (BlueClosureContracts contracts =
                     new BlueClosureContracts(owner, capture)) {
            attempt = contracts.admitClosureWithLifecycleQueue(input);
        }
        assertEquals(
                input.admissionCandidate().kind(),
                verification.candidateKind());
        assertTrue(attempt.isComplete());
        assertEquals(
                expectedCategory(input.admissionCandidate().kind()),
                attempt.processResult().diagnostic().category());
        assertCompleteEmptyEvidence(input, capture.evidence);
        return attempt.processResult();
    }

    private static void assertCompleteEmptyEvidence(
            ClosureInvocationInput input,
            ClosureImplementationEvidence evidence) {
        assertNotNull(evidence);
        assertTrue(evidence.complete());
        assertEquals(input.invocationIdentity(),
                evidence.invocationIdentity());
        assertTrue(evidence.workTrace().isEmpty());
        assertTrue(evidence.documentStepTrace().isEmpty());
        assertTrue(evidence.tentativeFinalizations().isEmpty());
        assertNull(evidence.nonConformanceCode());
    }

    private static ProcessorErrorCategory expectedCategory(
            AdmissionCandidate.Kind kind) {
        switch (kind) {
            case BAD_CYCLIC_PROOF:
                return ProcessorErrorCategory.CyclicSetProofInvalid;
            case AMBIGUOUS_PRELIMINARY_MEMBERS:
                return ProcessorErrorCategory
                        .CyclicPreliminaryMemberAmbiguous;
            case INVALID_OCCURRENCE_BINDING:
                return ProcessorErrorCategory
                        .ManagedOccurrenceBindingMissing;
            default:
                throw new AssertionError("Unexpected candidate kind " + kind);
        }
    }

    private static void assertRollback(
            AffectedClosureSnapshot input,
            ClosureProcessResult result,
            long totalGas,
            ProcessorErrorCategory category) {
        assertEquals(ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                result.status());
        assertFalse(result.commits());
        assertTrue(result.atomic());
        assertTrue(result.rollbackToInput());
        assertEquals(input.closureIdentity(), result.inputClosureIdentity());
        assertEquals(input.closureIdentity(), result.outputClosureIdentity());
        assertEquals(input.graphGeneration(), result.graphGeneration());
        assertEquals(input.managedDocuments().size(),
                result.resultingDocuments().size());
        assertEquals(input.components(), result.resultingComponents());
        assertEquals(input.occurrences(), result.occurrenceBindings());
        assertTrue(result.graphChanges().isEmpty());
        assertTrue(result.subscriptionDeltas().isEmpty());
        assertTrue(result.checkpointWrites().isEmpty());
        assertTrue(result.publicEvents().isEmpty());
        assertEquals(totalGas, result.totalGas());
        assertEquals(category, result.diagnostic().category());
        assertEquals(
                candidateKind(category).name(),
                result.diagnostic().detail("admissionCandidateKind"));
        assertNull(result.rejectedCharge());
        assertNull(result.rejectedWorkOccurrence());
        assertNull(result.platformCommitCompanion());
    }

    private static AdmissionCandidate.Kind candidateKind(
            ProcessorErrorCategory category) {
        switch (category) {
            case CyclicSetProofInvalid:
                return AdmissionCandidate.Kind.BAD_CYCLIC_PROOF;
            case CyclicPreliminaryMemberAmbiguous:
                return AdmissionCandidate.Kind
                        .AMBIGUOUS_PRELIMINARY_MEMBERS;
            case ManagedOccurrenceBindingMissing:
                return AdmissionCandidate.Kind.INVALID_OCCURRENCE_BINDING;
            default:
                throw new AssertionError("Unexpected category " + category);
        }
    }

    private static void assertTraceShape(
            ClosureProcessResult result,
            List<ExpectedEntry> expected) {
        assertEquals(expected.size(), result.gasTrace().size());
        for (int index = 0; index < expected.size(); index++) {
            ExpectedEntry entry = expected.get(index);
            assertEquals(index, result.gasTrace().get(index).sequence());
            assertEntry(
                    result.gasTrace().get(index),
                    entry.namespace,
                    entry.counter,
                    entry.quantity);
        }
    }

    private static void assertEntry(
            GasTraceEntry actual,
            String namespace,
            String counter,
            long quantity) {
        assertEquals(namespace, actual.namespace().wireValue());
        assertEquals(counter, actual.counter());
        assertEquals(quantity, actual.quantity());
    }

    private static ExpectedEntry entry(
            String namespace, String counter, long quantity) {
        return new ExpectedEntry(namespace, counter, quantity);
    }

    private static ClosureInvocationInput admission(
            DocumentProcessor owner,
            AffectedClosureSnapshot snapshot,
            AdmissionCandidate candidate) {
        ExecutionPolicy provisionalPolicy = new ExecutionPolicy(
                hash('0'),
                100000L,
                Collections.<DocumentId, Long>emptyMap(),
                "release-default");
        ExecutionPolicy policy = new ExecutionPolicy(
                IDENTITIES.executionPolicyIdentity(provisionalPolicy),
                provisionalPolicy.sharedLimit(),
                provisionalPolicy.localLimits(),
                provisionalPolicy.label());
        String admissionPolicy = hash('a');
        AdmissionCause cause = new AdmissionCause(
                IDENTITIES.admissionCauseIdentity(
                        AdmissionKind.TOP_LEVEL_ADMISSION,
                        "released-negative-vector",
                        null,
                        null,
                        admissionPolicy),
                AdmissionKind.TOP_LEVEL_ADMISSION,
                "released-negative-vector",
                null,
                null,
                admissionPolicy);
        ClosureEnvironment environment = environment(owner);
        String candidateIdentity =
                IDENTITIES.admissionCandidateIdentity(candidate);
        String emptyDeliveries = IDENTITIES.directDeliverySnapshotIdentity(
                Collections.<DirectLogicalDelivery>emptyList());
        ClosureInvocationInput provisional =
                ClosureInvocationInput.admitClosure(
                        hash('1'),
                        snapshot,
                        cause,
                        candidate,
                        candidateIdentity,
                        emptyDeliveries,
                        policy,
                        environment);
        return ClosureInvocationInput.admitClosure(
                IDENTITIES.invocationIdentity(provisional),
                snapshot,
                cause,
                candidate,
                candidateIdentity,
                emptyDeliveries,
                policy,
                environment);
    }

    private static ClosureEnvironment environment(DocumentProcessor owner) {
        ClosureRuntimeDescriptor runtime =
                ClosureRuntimeDescriptor.capture(owner);
        ClosureEnvironment.PortableLimitPolicyEvidence provisional =
                new ClosureEnvironment.PortableLimitPolicyEvidence(
                        hash('2'),
                        "blue-contracts-1.0-portable-limits",
                        GasSchedule.contracts10().portableLimits());
        return new ClosureEnvironment(
                hash('3'),
                hash('4'),
                runtime.runtimeRegistryIdentity(),
                runtime.gasManifestIdentity(),
                labeled(
                        ClosureIdentityService.Constructor
                                .MANAGED_DOCUMENT_IDENTITY_POLICY,
                        "nfc-document-lineage-v1"),
                labeled(
                        ClosureIdentityService.Constructor
                                .MANAGED_BINDING_POLICY,
                        BINDING_POLICY_LABEL),
                labeled(
                        ClosureIdentityService.Constructor
                                .EXACT_NODE_PROVIDER_DOMAIN,
                        "fixture-exact-node-provider-v1"),
                labeled(
                        ClosureIdentityService.Constructor
                                .EXTERNAL_ORDER_POLICY,
                        "canonical-source-order-v1"),
                new ClosureEnvironment.PortableLimitPolicyEvidence(
                        IDENTITIES.portableLimitPolicyIdentity(provisional),
                        provisional.label(),
                        provisional.limits()),
                ClosureRuntimeDescriptor.CYCLIC_FINALIZER_IDENTITY,
                ClosureRuntimeDescriptor.CYCLIC_PROOF_VERIFIER_IDENTITY);
    }

    private static ClosureEnvironment.LabeledIdentityEvidence labeled(
            ClosureIdentityService.Constructor constructor,
            String label) {
        return new ClosureEnvironment.LabeledIdentityEvidence(
                IDENTITIES.labeledIdentity(constructor, label), label);
    }

    private static AffectedClosureSnapshot acyclicSnapshot(
            DocumentId documentId, Node document) {
        String blueId = blue.language.identity.DirectBlueIdCalculator
                .calculateBlueId(document);
        ComponentSnapshot provisionalComponent = new ComponentSnapshot(
                IDENTITIES.componentIdentity(
                        ComponentKind.ACYCLIC,
                        0L,
                        Collections.singletonList(documentId)),
                hash('5'),
                0L,
                ComponentKind.ACYCLIC,
                Collections.singletonList(documentId),
                Collections.singletonList(blueId),
                null,
                null,
                null);
        ComponentSnapshot component = new ComponentSnapshot(
                provisionalComponent.componentIdentity(),
                IDENTITIES.componentStateIdentity(provisionalComponent),
                0L,
                ComponentKind.ACYCLIC,
                provisionalComponent.orderedMemberDocumentIds(),
                provisionalComponent.orderedMemberBlueIds(),
                null,
                null,
                null);
        ManagedDocumentSnapshot managed = new ManagedDocumentSnapshot(
                documentId,
                blueId,
                document,
                false,
                false,
                true,
                0L,
                0L);
        return snapshot(
                1L,
                Collections.singletonList(managed),
                Collections.<ManagedOccurrenceBinding>emptyList(),
                Collections.singletonList(component),
                Collections.singletonList(documentId));
    }

    private static CyclicState cyclicState() {
        Map<DocumentId, Node> bodies = new LinkedHashMap<DocumentId, Node>();
        bodies.put(SIMPLE_A, simpleA(MASTER + "#1"));
        bodies.put(SIMPLE_B, simpleB(MASTER + "#0"));
        List<ManagedOccurrenceBinding> bindings = Arrays.asList(
                binding(SIMPLE_A, "/b", SIMPLE_B, MASTER + "#1"),
                binding(SIMPLE_B, "/a", SIMPLE_A, MASTER + "#0"));
        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(
                bodies.keySet(), bindings);
        Map<DocumentId, Long> generations =
                new LinkedHashMap<DocumentId, Long>();
        generations.put(SIMPLE_A, Long.valueOf(1L));
        generations.put(SIMPLE_B, Long.valueOf(1L));
        ComponentFinalizationResult finalized =
                new ComponentFinalizationKernel().finalizeComponents(
                        new ComponentFinalizationInput(
                                graph, generations, bodies, bindings));
        ArrayList<ManagedDocumentSnapshot> documents =
                new ArrayList<ManagedDocumentSnapshot>();
        for (DocumentId documentId : Arrays.asList(SIMPLE_A, SIMPLE_B)) {
            FinalizedDocumentEvidence document =
                    finalized.document(documentId);
            documents.add(new ManagedDocumentSnapshot(
                    documentId,
                    document.blueId(),
                    document.document(),
                    false,
                    false,
                    documentId.equals(SIMPLE_A),
                    0L,
                    document.componentGeneration()));
        }
        ArrayList<ComponentSnapshot> components =
                new ArrayList<ComponentSnapshot>();
        for (FinalizedComponentEvidence component : finalized.components()) {
            components.add(component.component());
        }
        AffectedClosureSnapshot snapshot = snapshot(
                1L,
                documents,
                finalized.finalizedGraph().bindings(),
                components,
                Collections.singletonList(SIMPLE_A));
        return new CyclicState(snapshot);
    }

    private static AffectedClosureSnapshot snapshot(
            long graphGeneration,
            List<ManagedDocumentSnapshot> documents,
            List<ManagedOccurrenceBinding> occurrences,
            List<ComponentSnapshot> components,
            List<DocumentId> publicRoots) {
        String occurrenceSet = IDENTITIES.occurrenceBindingSetIdentity(
                occurrences);
        AffectedClosureSnapshot provisional = new AffectedClosureSnapshot(
                hash('6'),
                graphGeneration,
                documents,
                occurrences,
                occurrenceSet,
                components,
                publicRoots);
        return new AffectedClosureSnapshot(
                IDENTITIES.affectedClosureIdentity(provisional),
                graphGeneration,
                documents,
                occurrences,
                occurrenceSet,
                components,
                publicRoots);
    }

    private static List<AdmissionCandidate.CandidateMemberState> memberStates(
            ComponentSnapshot component) {
        ArrayList<AdmissionCandidate.CandidateMemberState> result =
                new ArrayList<AdmissionCandidate.CandidateMemberState>();
        for (int index = 0;
                index < component.orderedMemberDocumentIds().size();
                index++) {
            result.add(new AdmissionCandidate.CandidateMemberState(
                    component.orderedMemberDocumentIds().get(index),
                    component.orderedMemberBlueIds().get(index)));
        }
        return result;
    }

    private static ManagedOccurrenceBinding binding(
            DocumentId source,
            String path,
            DocumentId target,
            String expectedTargetBlueId) {
        ScopeAddress address = ScopeAddress.embedded(path, 1L);
        return new ManagedOccurrenceBinding(
                IDENTITIES.managedOccurrenceIdentity(
                        source, address, target, BINDING_POLICY),
                IDENTITIES.managedOccurrenceBindingIdentity(
                        source,
                        address,
                        target,
                        expectedTargetBlueId,
                        BINDING_POLICY),
                BINDING_POLICY,
                source,
                address,
                target,
                expectedTargetBlueId,
                true,
                null);
    }

    private static Node simpleA(String targetBlueId) {
        return new Node().properties(
                "documentId", reference(
                        "3fbe7KHmQAtqGDkqzPrPkfhJCD1nMFXJa9ckCUZxxxNR"),
                "memberIdentity", reference(
                        "3fbe7KHmQAtqGDkqzPrPkfhJCD1nMFXJa9ckCUZxxxNR"),
                "b", reference(targetBlueId),
                "contracts", reference(
                        "Fiod9ArSxfZhdRe3rC5dqM78x2CBfW3zdzSbaY9a6ujg"));
    }

    private static Node simpleB(String targetBlueId) {
        return new Node().properties(
                "documentId", reference(
                        "8i8RsDeMbU4U3nudF7pWdTH1imR2aRenUqnXb6xAWu3a"),
                "memberIdentity", reference(
                        "8i8RsDeMbU4U3nudF7pWdTH1imR2aRenUqnXb6xAWu3a"),
                "a", reference(targetBlueId),
                "contracts", reference(
                        "fyDiUQNFeL6UYVyezYDUYSbHfPX6gZCTQwsJudgNGq9"));
    }

    private static Node ambiguousMember(String target) {
        return new Node().properties(
                "same", new Node().value(Boolean.TRUE),
                "other", reference(target));
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static String hash(char value) {
        char[] digits = new char[64];
        Arrays.fill(digits, value);
        return "sha256:" + new String(digits);
    }

    private static final class Capture implements ClosureExecutionObserver {
        private ClosureImplementationEvidence evidence;

        @Override
        public void onExecutionEvidence(
                ClosureImplementationEvidence value) {
            assertNull(evidence);
            evidence = value;
        }
    }

    private static final class CyclicState {
        private final AffectedClosureSnapshot snapshot;

        private CyclicState(AffectedClosureSnapshot snapshot) {
            this.snapshot = snapshot;
        }
    }

    private static final class ExpectedEntry {
        private final String namespace;
        private final String counter;
        private final long quantity;

        private ExpectedEntry(
                String namespace, String counter, long quantity) {
            this.namespace = namespace;
            this.counter = counter;
            this.quantity = quantity;
        }
    }
}
